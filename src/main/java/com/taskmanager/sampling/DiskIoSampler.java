package com.taskmanager.sampling;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.IO_COUNTERS;

/**
 * 磁盘 I/O 采样器：通过 JNA 调 Windows {@code GetProcessIoCounters} 获取当前进程累计读写字节数，
 * 差分计算读写速率（字节/秒）。
 * <p>
 * JNA 由 LWJGL 传递依赖在运行时提供；若缺失（非 Windows 或 JNA 不在 classpath），
 * 构造器探测失败后 {@link #isAvailable()} 返回 false，调用方降级显示「不支持」。
 */
public final class DiskIoSampler {
	private final boolean available;
	private long lastRead;
	private long lastWrite;
	private long lastTime;

	public DiskIoSampler() {
		boolean avail = false;
		try {
			Class.forName("com.sun.jna.platform.win32.Kernel32");
			avail = true;
		} catch (Throwable ignored) {
			avail = false;
		}
		this.available = avail;
	}

	public boolean isAvailable() {
		return available;
	}

	/** 采样一次，返回 [读速率, 写速率]（字节/秒）；无基线或不可用返回 null。 */
	public long[] sampleRate() {
		if (!available) {
			return null;
		}
		try {
			IO_COUNTERS counters = new IO_COUNTERS();
			if (!Kernel32.INSTANCE.GetProcessIoCounters(Kernel32.INSTANCE.GetCurrentProcess(), counters)) {
				return null;
			}
			long now = System.nanoTime();
			long read = counters.ReadTransferCount;
			long write = counters.WriteTransferCount;
			long[] result = null;
			if (lastTime > 0) {
				long dt = now - lastTime;
				if (dt > 0) {
					long readRate = Math.max(0, (read - lastRead) * 1_000_000_000L / dt);
					long writeRate = Math.max(0, (write - lastWrite) * 1_000_000_000L / dt);
					result = new long[]{readRate, writeRate};
				}
			}
			lastRead = read;
			lastWrite = write;
			lastTime = now;
			return result;
		} catch (Throwable ignored) {
			return null;
		}
	}
}
