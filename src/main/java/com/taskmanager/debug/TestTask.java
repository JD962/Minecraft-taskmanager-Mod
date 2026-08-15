package com.taskmanager.debug;

import com.taskmanager.api.ManagedTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试任务：一个真实的守护线程，循环执行计数任务，用于验证操作引擎的真实副作用。
 * <p>
 * - 暂停：setPaused(true) 后线程停止计数（协作式标志位）；
 * - 恢复：setPaused(false) 后继续计数；
 * - 终止：terminate() 中断线程使其退出；
 * - 优先级：setPriority() 映射到真实 Thread.setPriority。
 */
public final class TestTask implements ManagedTask {
	/** 优先级档位 → Java Thread 优先级 映射（3 档 = NORM_PRIORITY=5）。 */
	private static final int[] PRIORITY_MAP = {0, Thread.MIN_PRIORITY, 3, Thread.NORM_PRIORITY, 7, Thread.MAX_PRIORITY};

	private final String name;
	private final AtomicBoolean paused = new AtomicBoolean(false);
	private final AtomicLong counter = new AtomicLong(0);
	private volatile Thread worker;

	public TestTask(String name) {
		this.name = name;
	}

	public void start() {
		Thread t = new Thread(this::run, name);
		t.setDaemon(true);
		worker = t;
		t.start();
	}

	private void run() {
		Thread current = Thread.currentThread();
		worker = current;
		while (!current.isInterrupted()) {
			if (!paused.get()) {
				counter.incrementAndGet();
			}
			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				current.interrupt();
				break;
			}
		}
	}

	@Override
	public void setPaused(boolean paused) {
		this.paused.set(paused);
	}

	@Override
	public void terminate() {
		Thread t = worker;
		if (t != null) {
			t.interrupt();
		}
	}

	@Override
	public void restart() {
		Thread old = worker;
		if (old != null) {
			old.interrupt();
			try {
				old.join(1000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		counter.set(0);
		paused.set(false);
		start();
	}

	@Override
	public void setPriority(int level) {
		int mapped = PRIORITY_MAP[Math.clamp(level, 1, 5)];
		Thread t = worker;
		if (t != null) {
			t.setPriority(mapped);
		}
	}

	@Override
	public boolean isRunning() {
		Thread t = worker;
		return t != null && t.isAlive();
	}

	@Override
	public java.util.Set<Long> threadIds() {
		Thread t = worker;
		return t == null ? java.util.Set.of() : java.util.Set.of(t.threadId());
	}

	/** 任务名称。 */
	public String name() {
		return name;
	}

	/** 执行计数（用于验证暂停/恢复是否真实生效）。 */
	public long counter() {
		return counter.get();
	}

	/** 是否处于暂停状态。 */
	public boolean paused() {
		return paused.get();
	}

	/** 真实线程优先级（用于验证优先级调整是否真实生效）。 */
	public int threadPriority() {
		Thread t = worker;
		return t != null ? t.getPriority() : -1;
	}

	/** 真实线程状态。 */
	public String threadState() {
		Thread t = worker;
		return t != null ? t.getState().name() : "TERMINATED";
	}
}
