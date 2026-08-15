package com.taskmanager.debug;

import com.taskmanager.api.ProcessState;
import com.taskmanager.core.ProcessManager;
import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessCategory;
import com.taskmanager.model.ProcessSide;
import com.taskmanager.model.ThreadInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 进程表导出器（.prc）。
 * <p>
 * 文件为二进制结构（非纯文本，16 进制字节存储），每个槽位内的进程表数据经 gzip 压缩，
 * 并附加头尾 magic 标识与 CRC32 校验。文件头固定记录各槽位长度，读取不依赖槽位内容即可定位。
 * <p>
 * 两种导出模式：
 * <ul>
 *   <li><b>单次导出</b>：一次性快照，仅写单层（主层），无缓存层。</li>
 *   <li><b>实时导出</b>（默认）：参考 Android 11+ A/B 分区逻辑，双槽位（槽位 0/槽位 1）交替写入——
 *       每次将新数据写入非活动槽位（缓存层），旧数据保留在另一槽位，写完后校验头尾标识与 CRC32，
 *       通过后切换活动槽位（覆盖主层）；任一槽位损坏时读取可自动回退到另一槽位恢复。</li>
 * </ul>
 */
public final class PrcExporter {
	private static final PrcExporter INSTANCE = new PrcExporter();

	private static final byte[] MAGIC_FILE = {'T', 'M', 'P', 'R'};
	private static final byte[] MAGIC_SLOT_HEAD = {'S', 'L', 'T', 'H'};
	private static final byte[] MAGIC_SLOT_TAIL = {'S', 'L', 'T', 'E'};
	private static final int VERSION = 1;
	private static final int SLOT_COUNT_REALTIME = 2;
	private static final int SLOT_COUNT_ONCE = 1;

	/** 文件大小上限（防恶意/损坏文件导致 OOM）。 */
	private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
	/** 解压后数据上限（防 gzip bomb）。 */
	private static final int MAX_RAW_BYTES = 128 * 1024 * 1024;

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	/** 文件头长度：magic(4) + version(4) + slotCount(4) + activeSlot(4) + slot0Len(4) + slot1Len(4) = 24 字节。 */
	private static final int HEADER_LENGTH = 24;
	private static final int SLOT_META_LENGTH = 12;
	private static final int SLOT_TAIL_LENGTH = 4;

	/** 生命周期锁：保护启动/停止状态（realtimeRunning/realtimeWorker/realtimeFile/lastGoodPayload）。 */
	private final Object lifecycleLock = new Object();
	/** 写入锁：串行化实时写入。 */
	private final Object writeLock = new Object();

	private volatile boolean realtimeRunning = false;
	private volatile long realtimeIntervalMs = 1000L;
	private volatile Path realtimeFile;
	private volatile int activeSlot = 0;
	private volatile byte[] lastGoodPayload;
	private Thread realtimeWorker;

	/** 校验状态：不可变记录 + 单一 volatile 引用，保证读写一致。 */
	public record ValidationState(long time, String message) {
	}
	private volatile ValidationState validationState = new ValidationState(-1L, "");
	private final AtomicLong writeCount = new AtomicLong();

	private PrcExporter() {
	}

	public static PrcExporter getInstance() {
		return INSTANCE;
	}

	public static String timestampedName(String prefix, String suffix) {
		return prefix + "_" + LocalDateTime.now().format(TIMESTAMP) + suffix;
	}

	public static Path defaultDirectory() {
		Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("taskmanager");
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
		}
		return dir;
	}

	public static Path latestPrcFile() {
		try (var stream = Files.list(defaultDirectory())) {
			return stream.filter(p -> p.getFileName().toString().endsWith(".prc"))
				.max(Comparator.comparingLong(p -> p.toFile().lastModified()))
				.orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	// ==================== 单次导出 ====================

	/** 单次导出：一次性快照，仅写单层（主层），无缓存层。 */
	public boolean exportOnce(Path file) {
		synchronized (writeLock) {
			try {
				byte[] payload = gzip(serializeProcessTable());
				byte[] full = buildFullFile(SLOT_COUNT_ONCE, 0, payload, null);
				writeAtomically(file, full);
				recordWriteSuccess();
				return true;
			} catch (Exception e) {
				recordFailure("单次导出失败: " + e.getMessage());
				return false;
			}
		}
	}

	// ==================== 实时导出（A/B 双槽位） ====================

	/** 启动实时导出：创建带时间戳的文件，初始化双槽位，后台线程周期性写入。 */
	public boolean startRealtime(long intervalMs) {
		synchronized (lifecycleLock) {
			if (realtimeRunning) {
				return true;
			}
			Path file = defaultDirectory().resolve(timestampedName("进程表", ".prc"));
			try {
				byte[] payload = gzip(serializeProcessTable());
				byte[] full = buildFullFile(SLOT_COUNT_REALTIME, 0, payload, payload);
				writeAtomically(file, full);

				Thread worker = new Thread(this::realtimeLoop, "TaskManager-PrExport");
				worker.setDaemon(true);
				worker.setPriority(Thread.MIN_PRIORITY);

				// 先发布状态，再启动线程；启动失败则回滚全部状态
				realtimeFile = file;
				activeSlot = 0;
				lastGoodPayload = payload;
				realtimeIntervalMs = Math.max(100L, intervalMs);
				realtimeWorker = worker;
				realtimeRunning = true;
				try {
					worker.start();
				} catch (RuntimeException | Error e) {
					realtimeRunning = false;
					realtimeWorker = null;
					realtimeFile = null;
					lastGoodPayload = null;
					throw e;
				}
				recordWriteSuccess();
				return true;
			} catch (Exception e) {
				recordFailure("实时导出启动失败: " + e.getMessage());
				return false;
			}
		}
	}

	/** 停止实时导出：设置停止标志、中断并等待线程退出，做最后一次写入后清理状态。 */
	public boolean stopRealtime() {
		Thread worker;
		synchronized (lifecycleLock) {
			if (!realtimeRunning) {
				return false;
			}
			realtimeRunning = false;
			worker = realtimeWorker;
			if (worker != null) {
				worker.interrupt();
			}
		}
		if (worker != null && worker != Thread.currentThread()) {
			try {
				worker.join(5000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			if (worker.isAlive()) {
				recordFailure("实时导出线程未在超时时间内退出");
				return false;
			}
		}
		boolean ok = writeRealtimeSlot();
		synchronized (lifecycleLock) {
			realtimeWorker = null;
			realtimeFile = null;
			lastGoodPayload = null;
		}
		return ok;
	}

	public boolean isRealtimeRunning() {
		return realtimeRunning;
	}

	private void realtimeLoop() {
		while (realtimeRunning) {
			writeRealtimeSlot();
			try {
				Thread.sleep(realtimeIntervalMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	/**
	 * 实时写入：新数据写入非活动槽位（缓存层），旧数据保留在原活动槽位，
	 * 内存构建并校验后经临时文件原子替换；校验通过即切换活动槽位（覆盖主层）。
	 */
	private boolean writeRealtimeSlot() {
		Path file = realtimeFile;
		if (file == null) {
			return false;
		}
		synchronized (writeLock) {
			try {
				byte[] newPayload = gzip(serializeProcessTable());
				byte[] oldPayload = lastGoodPayload != null ? lastGoodPayload : newPayload;
				int newActive = 1 - activeSlot;

				byte[] slot0 = (newActive == 0) ? newPayload : oldPayload;
				byte[] slot1 = (newActive == 1) ? newPayload : oldPayload;
				byte[] full = buildFullFile(SLOT_COUNT_REALTIME, newActive, slot0, slot1);

				// 内存校验（不写临时文件、不修改全局状态）
				ReadResult check = readInternal(full);
				if (check == null || check.sourceSlot() != newActive || !Arrays.equals(check.payload(), newPayload)) {
					throw new IOException("新活动槽位校验失败");
				}
				writeAtomically(file, full);

				activeSlot = newActive;
				lastGoodPayload = newPayload;
				recordWriteSuccess();
				return true;
			} catch (Exception e) {
				recordFailure("实时写入失败: " + e.getMessage());
				return false;
			}
		}
	}

	// ==================== 读取 / 恢复 ====================

	/** 读取结果：有效数据（gzip 压缩）与其来源槽位。 */
	public record ReadResult(byte[] payload, int sourceSlot) {
	}

	/** 读取并校验文件：优先读活动槽位，损坏时回退到另一槽位；全部损坏返回 null。 */
	public ReadResult read(Path file) {
		try {
			long size = Files.size(file);
			if (size < HEADER_LENGTH || size > MAX_FILE_BYTES) {
				recordFailure("文件大小非法: " + size);
				return null;
			}
			ReadResult result = readInternal(Files.readAllBytes(file));
			recordValidation(result != null ? "校验通过" : "全部槽位损坏");
			return result;
		} catch (Exception e) {
			recordFailure("读取失败: " + e.getMessage());
			return null;
		}
	}

	/** 纯解析校验（不修改全局状态），供 read 与内存校验共用。 */
	private ReadResult readInternal(byte[] all) throws IOException {
		if (all.length < HEADER_LENGTH || !startsWith(all, 0, MAGIC_FILE)) {
			throw new IOException("文件头损坏");
		}
		int version = readInt(all, 4);
		if (version != VERSION) {
			throw new IOException("不支持的 PRC 版本: " + version);
		}
		int slotCount = readInt(all, 8);
		int active = readInt(all, 12);
		long slot0Len = readInt(all, 16) & 0xFFFFFFFFL;
		long slot1Len = readInt(all, 20) & 0xFFFFFFFFL;
		if (slotCount != SLOT_COUNT_ONCE && slotCount != SLOT_COUNT_REALTIME) {
			throw new IOException("非法槽位数量: " + slotCount);
		}
		if (active < 0 || active >= slotCount) {
			throw new IOException("非法活动槽位: " + active);
		}

		long[] offsets = new long[slotCount];
		long[] lengths = new long[slotCount];
		if (slotCount >= 1) {
			offsets[0] = HEADER_LENGTH;
			lengths[0] = slot0Len;
		}
		if (slotCount >= 2) {
			offsets[1] = HEADER_LENGTH + slot0Len;
			lengths[1] = slot1Len;
		}

		byte[] activePayload = null;
		byte[] fallbackPayload = null;
		int fallbackSlot = -1;
		for (int i = 0; i < slotCount; i++) {
			byte[] p = readSlotAt(all, offsets[i], lengths[i]);
			if (p != null) {
				if (i == active) {
					activePayload = p;
				} else if (fallbackPayload == null) {
					fallbackPayload = p;
					fallbackSlot = i;
				}
			}
		}

		if (activePayload != null) {
			return new ReadResult(activePayload, active);
		}
		if (fallbackPayload != null) {
			return new ReadResult(fallbackPayload, fallbackSlot);
		}
		throw new IOException("全部槽位损坏");
	}

	/** 解析单个槽位：校验头尾 magic + CRC32；用 long 计算避免整数溢出，损坏返回 null。 */
	private static byte[] readSlotAt(byte[] all, long offset, long length) {
		if (offset < 0 || offset > all.length || length < SLOT_META_LENGTH + SLOT_TAIL_LENGTH) {
			return null;
		}
		long end = offset + length;
		if (end > all.length) {
			return null;
		}
		if (!startsWith(all, (int) offset, MAGIC_SLOT_HEAD)) {
			return null;
		}
		long dataLen = readInt(all, (int) offset + 4) & 0xFFFFFFFFL;
		long expectedCrc = readInt(all, (int) offset + 8) & 0xFFFFFFFFL;
		long dataStart = offset + SLOT_META_LENGTH;
		if (dataStart + dataLen + SLOT_TAIL_LENGTH > end || dataLen > MAX_RAW_BYTES) {
			return null;
		}
		int dl = (int) dataLen;
		byte[] payload = new byte[dl];
		System.arraycopy(all, (int) dataStart, payload, 0, dl);
		if (!startsWith(all, (int) (dataStart + dl), MAGIC_SLOT_TAIL)) {
			return null;
		}
		if ((int) crc32(payload) != (int) expectedCrc) {
			return null;
		}
		return payload;
	}

	// ==================== 序列化（二进制格式） ====================

	/** 序列化当前进程表为二进制字节。 */
	private byte[] serializeProcessTable() throws IOException {
		List<Process> processes = ProcessManager.getInstance().all().stream()
			.sorted((a, b) -> Integer.compare(a.pid(), b.pid()))
			.toList();

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 * processes.size());
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(processes.size());
			for (Process p : processes) {
				writeString(out, p.name());
				writeString(out, p.source().id());
				writeString(out, p.source().displayName());
				out.writeByte(p.category() == ProcessCategory.ENTITY ? 0 : 1);
				out.writeByte(p.side() == ProcessSide.CLIENT ? 0 : 1);
				writeString(out, p.subCategory() == null ? "" : p.subCategory());
				out.writeInt(p.entityId());
				out.writeLong(p.createTime());
				out.writeByte(stateCode(p.state()));
				out.writeInt(p.priority());
				out.writeDouble(p.usage().cpuUsage());
				out.writeLong(p.usage().heapMemory());
				out.writeLong(p.usage().nonHeapMemory());
				out.writeDouble(p.usage().gpuUsage());

				List<ThreadInfo> threads = p.threads();
				out.writeInt(threads.size());
				for (ThreadInfo t : threads) {
					writeString(out, t.threadName());
					out.writeLong(t.threadId());
					out.writeLong(t.nativeId());
					out.writeDouble(t.usage().cpuUsage());
					out.writeLong(t.usage().heapMemory());
					out.writeLong(t.usage().nonHeapMemory());
					out.writeDouble(t.usage().gpuUsage());
				}
			}
		}
		return bytes.toByteArray();
	}

	// ==================== 压缩 / 构建 ====================

	private static byte[] gzip(byte[] raw) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
			gz.write(raw);
		}
		return bytes.toByteArray();
	}

	/** 解压 gzip 数据（带输出上限，防 gzip bomb）。 */
	public static byte[] ungzip(byte[] compressed) throws IOException {
		try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed));
		     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] buf = new byte[8192];
			int total = 0;
			int n;
			while ((n = gz.read(buf)) >= 0) {
				total += n;
				if (total > MAX_RAW_BYTES) {
					throw new IOException("解压数据超过限制");
				}
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	/** 从反序列化的进程表字节中读取进程数（校验用）。 */
	public static int countProcesses(byte[] raw) throws IOException {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
			return in.readInt();
		}
	}

	private static byte[] buildFullFile(int slotCount, int activeSlot, byte[] slot0Payload, byte[] slot1Payload) throws IOException {
		byte[] slot0 = buildSlot(slot0Payload);
		byte[] slot1 = (slotCount == SLOT_COUNT_REALTIME) ? buildSlot(slot1Payload) : new byte[0];

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(buildHeader(slotCount, activeSlot, slot0.length, slot1.length));
		out.write(slot0);
		out.write(slot1);
		return out.toByteArray();
	}

	private static byte[] buildSlot(byte[] payload) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + SLOT_META_LENGTH + SLOT_TAIL_LENGTH);
		try {
			out.write(MAGIC_SLOT_HEAD);
			out.write(intBytes(payload.length));
			out.write(intBytes((int) crc32(payload)));
			out.write(payload);
			out.write(MAGIC_SLOT_TAIL);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return out.toByteArray();
	}

	private static byte[] buildHeader(int slotCount, int activeSlot, int slot0Len, int slot1Len) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_LENGTH);
		try {
			out.write(MAGIC_FILE);
			out.write(intBytes(VERSION));
			out.write(intBytes(slotCount));
			out.write(intBytes(activeSlot));
			out.write(intBytes(slot0Len));
			out.write(intBytes(slot1Len));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return out.toByteArray();
	}

	/** 写临时文件 + force 刷盘 + 原子替换，失败时清理临时文件。 */
	private static void writeAtomically(Path file, byte[] content) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		Path tmp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
		boolean moved = false;
		try {
			try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				ByteBuffer buf = ByteBuffer.wrap(content);
				while (buf.hasRemaining()) {
					ch.write(buf);
				}
				ch.force(true);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			moved = true;
		} finally {
			if (!moved) {
				Files.deleteIfExists(tmp);
			}
		}
	}

	// ==================== 工具方法 ====================

	private static void writeString(DataOutputStream out, String s) throws IOException {
		if (s == null) {
			throw new IOException("必填字符串为 null");
		}
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	private static long crc32(byte[] data) {
		CRC32 crc = new CRC32();
		crc.update(data);
		return crc.getValue();
	}

	private static byte[] intBytes(int v) {
		return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
	}

	private static int readInt(byte[] data, int offset) {
		return ((data[offset] & 0xFF) << 24)
			| ((data[offset + 1] & 0xFF) << 16)
			| ((data[offset + 2] & 0xFF) << 8)
			| (data[offset + 3] & 0xFF);
	}

	private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
		if (offset < 0 || offset + prefix.length > data.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (data[offset + i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static int stateCode(ProcessState state) {
		return switch (state) {
			case RUNNING -> 0;
			case PAUSED -> 1;
			case TERMINATED -> 2;
			case PENDING_START -> 3;
		};
	}

	// ==================== 状态暴露 ====================

	private void recordWriteSuccess() {
		writeCount.incrementAndGet();
		validationState = new ValidationState(System.currentTimeMillis(), "校验通过");
	}

	private void recordFailure(String reason) {
		validationState = new ValidationState(System.currentTimeMillis(), reason);
	}

	private void recordValidation(String message) {
		validationState = new ValidationState(System.currentTimeMillis(), message);
	}

	public long lastWriteTime() {
		return validationState.time();
	}

	public String lastValidation() {
		return validationState.message();
	}

	public long writeCount() {
		return writeCount.get();
	}

	public Path realtimeFile() {
		return realtimeFile;
	}
}
