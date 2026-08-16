package com.taskmanager.debug;

import com.taskmanager.api.ManagedTask;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 受管任务：包装任意 {@link Runnable}，以真实守护线程运行，支持暂停/恢复/终止/重启/优先级。
 * <p>
 * 与 {@link TestTask}（内置计数）不同，本类的运行逻辑由外部传入，用于「运行新任务」反射启动
 * 用户指定的类（实现 {@link Runnable}）时纳入进程管理。
 */
public final class RunnableTask implements ManagedTask {
	private static final int[] PRIORITY_MAP = {0, Thread.MIN_PRIORITY, 3, Thread.NORM_PRIORITY, 7, Thread.MAX_PRIORITY};

	private final String name;
	private final Runnable runnable;
	private final AtomicBoolean paused = new AtomicBoolean(false);
	private final AtomicInteger priorityLevel = new AtomicInteger(3);
	private volatile Thread worker;

	public RunnableTask(String name, Runnable runnable) {
		this.name = name;
		this.runnable = runnable;
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
		try {
			runnable.run();
		} catch (Throwable ignored) {
			// 任务异常退出不拖垮 JVM
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
		paused.set(false);
		start();
	}

	@Override
	public void setPriority(int level) {
		int clamped = Math.clamp(level, 1, 5);
		priorityLevel.set(clamped);
		Thread t = worker;
		if (t != null) {
			t.setPriority(PRIORITY_MAP[clamped]);
		}
	}

	@Override
	public boolean isRunning() {
		Thread t = worker;
		return t != null && t.isAlive();
	}

	@Override
	public Set<Long> threadIds() {
		Thread t = worker;
		return t == null ? Set.of() : Set.of(t.threadId());
	}

	public String name() {
		return name;
	}

	public boolean paused() {
		return paused.get();
	}
}
