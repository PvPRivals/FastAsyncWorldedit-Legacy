package com.boydti.fawe.util;

import com.boydti.fawe.config.Settings;
import com.boydti.fawe.object.FaweQueue;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A SetQueue scheduler that can start work between regular server ticks and limits
 * its cumulative main-thread time in fixed 50 millisecond windows.
 */
public class SubtickSetQueue extends SetQueue {

    private static final long WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long MIN_BATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final AtomicBoolean pumpScheduled = new AtomicBoolean();
    private final AtomicBoolean wakeScheduled = new AtomicBoolean();
    private final Runnable pump = this::pump;
    private final Set<FaweQueue> processedThisWindow = new HashSet<>();

    private long windowStartNanos;
    private long usedNanos;
    private boolean deferUntilNextWindow;

    public SubtickSetQueue() {
        super(false);
    }

    @Override
    protected void onWorkAdded() {
        requestPump();
    }

    private void requestPump() {
        if (TaskManager.IMP == null || !pumpScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            TaskManager.IMP.taskSubtick(pump);
        } catch (Throwable throwable) {
            pumpScheduled.set(false);
            MainUtil.handleError(throwable);
        }
    }

    private void requestNextWindow() {
        if (!wakeScheduled.compareAndSet(false, true)) {
            return;
        }
        long delayNanos = nanosUntilNextWindow(System.nanoTime());
        TaskManager.IMP.taskSubtickLater(() -> {
            wakeScheduled.set(false);
            requestPump();
        }, delayNanos);
    }

    private long nanosUntilNextWindow(long now) {
        if (windowStartNanos == 0) {
            return 0;
        }
        return Math.max(0, windowStartNanos + WINDOW_NANOS - now);
    }

    private void pump() {
        deferUntilNextWindow = false;
        try {
            processWindow();
        } catch (Throwable throwable) {
            MainUtil.handleError(throwable);
        } finally {
            pumpScheduled.set(false);
            if (hasPendingWork()) {
                if (!deferUntilNextWindow
                        && remainingNanos(System.nanoTime()) >= MIN_BATCH_NANOS) {
                    requestPump();
                } else {
                    requestNextWindow();
                }
            } else {
                runEmptyTasks();
            }
        }
    }

    private void processWindow() {
        long started = System.nanoTime();
        long remaining = remainingNanos(started);
        if (remaining < MIN_BATCH_NANOS) {
            return;
        }

        long deadline = started + remaining;
        try {
            processMiscTasks(deadline);
            if (System.nanoTime() >= deadline) {
                return;
            }

            if (activeQueues.isEmpty() && inactiveQueues.isEmpty()) {
                runEmptyTasks();
                return;
            }

            if (!MemUtil.isMemoryFree()) {
                int memory = MemUtil.calculateMemory();
                if (memory != Integer.MAX_VALUE && memory <= 1 && Settings.IMP.PREVENT_CRASHES) {
                    for (FaweQueue queue : getAllQueues()) {
                        queue.saveMemory();
                    }
                    return;
                }
            }

            int maxQueues = Math.max(1, Settings.IMP.QUEUE.MAX_QUEUES_PER_TICK);
            int availableQueues = activeQueues.isEmpty() ? inactiveQueues.size() : activeQueues.size();
            int queueLimit = Math.min(maxQueues,
                    Math.max(1, availableQueues));
            for (int i = 0; i < queueLimit; i++) {
                long now = System.nanoTime();
                if (deadline - now < MIN_BATCH_NANOS) {
                    break;
                }
                FaweQueue queue = getNextQueue();
                if (queue == null) {
                    deferUntilNextWindow = true;
                    break;
                }
                if (!processedThisWindow.contains(queue)
                        && processedThisWindow.size() >= maxQueues) {
                    deferUntilNextWindow = true;
                    break;
                }
                processedThisWindow.add(queue);
                int queuesLeft = queueLimit - i;
                long queueNanos = (deadline - now) / queuesLeft;
                processQueue(queue, Math.max(1, TimeUnit.NANOSECONDS.toMillis(queueNanos)));
            }
        } finally {
            charge(started, System.nanoTime());
        }
    }

    private void processMiscTasks(long deadline) {
        long now = System.nanoTime();
        long taskDeadline = activeQueues.isEmpty() ? deadline : now + ((deadline - now) >> 1);
        while (taskDeadline - System.nanoTime() >= MIN_BATCH_NANOS) {
            Runnable task = tasks.poll();
            if (task == null) {
                return;
            }
            task.run();
        }
    }

    private boolean hasPendingWork() {
        return !tasks.isEmpty() || !activeQueues.isEmpty() || !inactiveQueues.isEmpty();
    }

    private long remainingNanos(long now) {
        advanceWindow(now);
        long budget = TimeUnit.MILLISECONDS.toNanos(
                Math.max(1, Math.min(50, Settings.IMP.QUEUE.SUBTICK.MAX_TIME_MS)));
        long budgetRemaining = Math.max(0, budget - usedNanos);
        long windowRemaining = Math.max(0, windowStartNanos + WINDOW_NANOS - now);
        return Math.min(budgetRemaining, windowRemaining);
    }

    private void advanceWindow(long now) {
        if (windowStartNanos == 0) {
            windowStartNanos = now;
            processedThisWindow.clear();
            return;
        }
        if (now - windowStartNanos >= WINDOW_NANOS) {
            long windows = (now - windowStartNanos) / WINDOW_NANOS;
            windowStartNanos += windows * WINDOW_NANOS;
            usedNanos = 0;
            processedThisWindow.clear();
        }
    }

    private void charge(long started, long finished) {
        long windowEnd = windowStartNanos + WINDOW_NANOS;
        if (finished < windowEnd) {
            usedNanos += finished - started;
            return;
        }

        long windows = (finished - windowStartNanos) / WINDOW_NANOS;
        windowStartNanos += windows * WINDOW_NANOS;
        usedNanos = finished - windowStartNanos;
        processedThisWindow.clear();
    }
}
