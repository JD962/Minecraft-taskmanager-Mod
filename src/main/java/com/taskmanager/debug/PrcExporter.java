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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 进程表导出器（.prc）。
 * <p>
 * 文件为二进制结构（非纯文本，16 进制字节存储），每个槽位内的进程表数据经 gzip 压缩，
 * 并附加头尾 magic 标识与 CRC32 校验。
 * <p>
 * 文件头固定记录各槽位长度，读取时不依赖槽位内容即可定位，保证任一槽位损坏时可从另一槽位恢复。
 * <p>
 * 两种导出模式：
 * <ul>
 *   <li><b>单次导出</b>：一次性快照，仅写单层（主层），无缓存层。</li>
 *   <li><b>实时导出</b>（默认）：参考 Android 11+ A/B 分区逻辑，双槽位（槽位 0/槽位 1）交替写入——
 *       每次将新数据写入非活动槽位（缓存层），旧数据保留在另一槽位，写完后校验头尾标识与 CRC32，
 *       通过后切换活动槽位（覆盖主层）；任一槽位损坏时读取可自动回退到另一槽位恢复。
 *       双槽位固定仅保留两份，降低多份空间占用。</li>
 * </ul>
 */
public final class PrcExporter {
	private static final PrcExporter INSTANCE = new PrcExporter();

	/** 文件头标识。 */
	private static final byte[] MAGIC_FILE = {'T', 'M', 'P', 'R'};
	/** 槽位头标识。 */
	private static final byte[] MAGIC_SLOT_HEAD = {'S', 'L', 'T', 'H'};
	/** 槽位尾标识。 */
	private static final byte[] MAGIC_SLOT_TAIL = {'S', 'L', 'T', 'E'};
	/** 格式版本。 */
	private static final int VERSION = 1;
	private static final int SLOT_COUNT_REALTIME = 2;
	private static final int SLOT_COUNT_ONCE = 1;

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	/**
	 * 文件头长度：magic(4) + version(4) + slotCount(4) + activeSlot(4) + slot0Len(4) + slot1Len(4) = 24 字节。
	 * 头部固定记录槽位长度，读取定位不依赖槽位内容，保证单槽位损坏仍可定位另一槽位。
	 */
	private static final int HEADER_LENGTH = 24;
	/** 槽位元数据长度：头 magic(4) + dataLength(4) + crc(4) = 12 字节。 */
	private static final int SLOT_META_LENGTH = 12;
	private static final int SLOT_TAIL_LENGTH = 4;

	private Thread realtimeWorker;
	private final Object writeLock = new Object();
	private volatile boolean realtimeRunning = false;
	private volatile long realtimeIntervalMs = 1000L;
	private volatile Path realtimeFile;
	private volatile int activeSlot = 0;
	/** 上一次有效压缩数据（缓存层内容来源，避免重复解析文件）。 */
	private volatile byte[] lastGoodPayload;

	private volatile long lastWriteTime = -1L;
	private volatile String lastValidation = "";
	private volatile long writeCount = 0L;

	private PrcExporter() {
	}

	public static PrcExporter getInstance() {
		return INSTANCE;
	}

	/** 生成带时间戳的导出文件名。 */
	public static String timestampedName(String prefix, String suffix) {
		return prefix + "_" + LocalDateTime.now().format(TIMESTAMP) + suffix;
	}

	/** 默认导出目录：游戏目录下的 taskmanager 子目录。 */
	public static Path defaultDirectory() {
		Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("taskmanager");
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
		}
		return dir;
	}

	/** 查找导出目录下最新的 .prc 文件，无则返回 null。 */
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
				markSuccess();
				return true;
			} catch (Exception e) {
				markFailure("单次导出失败: " + e.getMessage());
				return false;
			}
		}
	}

	// ==================== 实时导出（A/B 双槽位） ====================

	/** 启动实时导出：创建带时间戳的文件，初始化双槽位，后台线程周期性写入。 */
	public boolean startRealtime(long intervalMs) {
		if (realtimeRunning) {
			return true;
		}
		Path file = defaultDirectory().resolve(timestampedName("进程表", ".prc"));
		synchronized (writeLock) {
			try {
				byte[] payload = gzip(serializeProcessTable());
				byte[] full = buildFullFile(SLOT_COUNT_REALTIME, 0, payload, payload);
				writeAtomically(file, full);

				realtimeFile = file;
				activeSlot = 0;
				lastGoodPayload = payload;
				realtimeIntervalMs = Math.max(100L, intervalMs);
				realtimeRunning = true;
				realtimeWorker = new Thread(this::realtimeLoop, "TaskManager-PrExport");
				realtimeWorker.setDaemon(true);
				realtimeWorker.setPriority(Thread.MIN_PRIORITY);
				realtimeWorker.start();
				markSuccess();
				return true;
			} catch (Exception e) {
				markFailure("实时导出启动失败: " + e.getMessage());
				return false;
			}
		}
	}

	/** 停止实时导出并做最后一次写入。 */
	public boolean stopRealtime() {
		if (!realtimeRunning) {
			return false;
		}
		realtimeRunning = false;
		if (realtimeWorker != null) {
			realtimeWorker.interrupt();
			realtimeWorker = null;
		}
		writeRealtimeSlot();
		realtimeFile = null;
		lastGoodPayload = null;
		return true;
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
	 * 整体构建文件后经临时文件原子替换；校验通过即切换活动槽位（覆盖主层）。
	 */
	private void writeRealtimeSlot() {
		Path file = realtimeFile;
		if (file == null) {
			return;
		}
		synchronized (writeLock) {
			try {
				byte[] newPayload = gzip(serializeProcessTable());
				byte[] oldPayload = lastGoodPayload != null ? lastGoodPayload : newPayload;
				int newActive = 1 - activeSlot;

				// 新数据放入新的活动槽位，旧数据放入另一槽位
				byte[] slot0 = (newActive == 0) ? newPayload : oldPayload;
				byte[] slot1 = (newActive == 1) ? newPayload : oldPayload;
				byte[] full = buildFullFile(SLOT_COUNT_REALTIME, newActive, slot0, slot1);

				Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
				Files.write(tmp, full);
				// 读回校验缓存层（头尾 magic + CRC32），并确认活动槽位指向新数据
				ReadResult check = read(tmp);
				if (check == null || check.sourceSlot() != newActive) {
					Files.deleteIfExists(tmp);
					throw new IOException("缓存层校验未通过");
				}
				Files.deleteIfExists(tmp);
				writeAtomically(file, full);

				activeSlot = newActive;
				lastGoodPayload = newPayload;
				markSuccess();
			} catch (Exception e) {
				markFailure("实时写入失败: " + e.getMessage());
			}
		}
	}

	// ==================== 读取 / 恢复 ====================

	/** 读取结果：有效数据（gzip 压缩）与其来源槽位。 */
	public record ReadResult(byte[] payload, int sourceSlot) {
	}

	/**
	 * 读取并校验文件：优先读活动槽位，损坏时回退到另一槽位恢复；全部损坏返回 null。
	 * 返回数据为 gzip 压缩的进程表。
	 */
	public ReadResult read(Path file) {
		try {
			byte[] all = Files.readAllBytes(file);
			if (all.length < HEADER_LENGTH || !startsWith(all, 0, MAGIC_FILE)) {
				markFailure("文件头损坏");
				return null;
			}
			int slotCount = readInt(all, 8);
			int active = readInt(all, 12);
			int slot0Len = readInt(all, 16);
			int slot1Len = readInt(all, 20);

			// 依头部记录定位各槽位
			int[] offsets = new int[slotCount];
			int[] lengths = new int[slotCount];
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
				markSuccess();
				return new ReadResult(activePayload, active);
			}
			if (fallbackPayload != null) {
				markSuccess();
				return new ReadResult(fallbackPayload, fallbackSlot);
			}
			markFailure("全部槽位损坏");
			return null;
		} catch (Exception e) {
			markFailure("读取失败: " + e.getMessage());
			return null;
		}
	}

	/** 解析单个槽位：校验头尾 magic + CRC32，返回 payload；损坏返回 null。 */
	private static byte[] readSlotAt(byte[] all, int offset, int length) {
		if (offset + length > all.length || length < SLOT_META_LENGTH + SLOT_TAIL_LENGTH) {
			return null;
		}
		if (!startsWith(all, offset, MAGIC_SLOT_HEAD)) {
			return null;
		}
		int dataLength = readInt(all, offset + 4);
		int expectedCrc = readInt(all, offset + 8);
		int dataStart = offset + SLOT_META_LENGTH;
		if (dataLength < 0 || dataStart + dataLength + SLOT_TAIL_LENGTH > offset + length) {
			return null;
		}
		byte[] payload = new byte[dataLength];
		System.arraycopy(all, dataStart, payload, 0, dataLength);
		if (!startsWith(all, dataStart + dataLength, MAGIC_SLOT_TAIL)) {
			return null;
		}
		if ((int) crc32(payload) != expectedCrc) {
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

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
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

	// ==================== 压缩 / 校验 / 构建 ====================

	private static byte[] gzip(byte[] raw) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
			gz.write(raw);
		}
		return bytes.toByteArray();
	}

	/** 解压 gzip 数据（供读取恢复与校验使用）。 */
	public static byte[] ungzip(byte[] compressed) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			gz.transferTo(out);
		}
		return out.toByteArray();
	}

	/** 从反序列化的进程表字节中读取进程数（校验用）。 */
	public static int countProcesses(byte[] raw) throws IOException {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
			return in.readInt();
		}
	}

	/** 构建完整文件：文件头（含槽位长度）+ 槽位 0 + 槽位 1。 */
	private static byte[] buildFullFile(int slotCount, int activeSlot, byte[] slot0Payload, byte[] slot1Payload) throws IOException {
		byte[] slot0 = buildSlot(slot0Payload);
		byte[] slot1 = (slotCount == SLOT_COUNT_REALTIME) ? buildSlot(slot1Payload) : new byte[0];

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(buildHeader(slotCount, activeSlot, slot0.length, slot1.length));
		out.write(slot0);
		out.write(slot1);
		return out.toByteArray();
	}

	/** 构建单个槽位：头 magic + 长度 + CRC32 + 数据 + 尾 magic。 */
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

	/** 写临时文件 + 原子替换，保证目标文件始终完整有效。 */
	private static void writeAtomically(Path file, byte[] content) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
		Files.write(tmp, content);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// ==================== 工具方法 ====================

	private static void writeString(DataOutputStream out, String s) throws IOException {
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
		if (offset + prefix.length > data.length) {
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

	private void markSuccess() {
		lastWriteTime = System.currentTimeMillis();
		lastValidation = "校验通过";
		writeCount++;
	}

	private void markFailure(String reason) {
		lastWriteTime = System.currentTimeMillis();
		lastValidation = reason;
	}

	public long lastWriteTime() {
		return lastWriteTime;
	}

	public String lastValidation() {
		return lastValidation;
	}

	public long writeCount() {
		return writeCount;
	}

	public Path realtimeFile() {
		return realtimeFile;
	}
}
