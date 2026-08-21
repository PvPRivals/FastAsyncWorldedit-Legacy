package com.boydti.fawe.bukkit.util;

import com.boydti.fawe.Fawe;
import com.boydti.fawe.util.TaskManager;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.commons.lang.mutable.MutableInt;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class BukkitTaskMan extends TaskManager {

    private final Plugin plugin;
    private final ScheduledExecutorService subtickTimer;

    public BukkitTaskMan(final Plugin plugin) {
        this.plugin = plugin;
        this.subtickTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "FAWE subtick timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public int repeat(final Runnable r, final int interval) {
        return this.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(this.plugin, r, interval, interval);
    }

    @Override
    public int repeatAsync(final Runnable r, final int interval) {
        return this.plugin.getServer().getScheduler().scheduleAsyncRepeatingTask(this.plugin, r, interval, interval);
    }

    public MutableInt index = new MutableInt(0);

    @Override
    public void async(final Runnable r) {
        if (r == null) {
            return;
        }
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, r).getTaskId();
    }

    @Override
    public void task(final Runnable r) {
        if (r == null) {
            return;
        }
        this.plugin.getServer().getScheduler().runTask(this.plugin, r).getTaskId();
    }

    @Override
    public void taskSubtick(final Runnable r) {
        if (r == null) {
            return;
        }
        this.plugin.getServer().getScheduler().callSyncMethod(this.plugin, () -> {
            r.run();
            return null;
        });
    }

    @Override
    public boolean supportsSubtickTasks() {
        return true;
    }

    @Override
    public void taskSubtickLater(final Runnable r, long delayNanos) {
        if (r == null) {
            return;
        }
        if (delayNanos <= 0) {
            taskSubtick(r);
            return;
        }
        subtickTimer.schedule(() -> taskSubtick(r), delayNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void shutdown() {
        subtickTimer.shutdownNow();
    }

    @Override
    public <T> T sync(final Supplier<T> function, int timeout) {
        if (Fawe.isMainThread()) {
            return function.get();
        }

        Future<T> future = this.plugin.getServer().getScheduler().callSyncMethod(this.plugin, function::get);
        try {
            if (timeout == Integer.MAX_VALUE) {
                return future.get();
            }
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            future.cancel(false);
            throw new RuntimeException("Timed out waiting for a main-thread task", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    @Override
    public void later(final Runnable r, final int delay) {
        if (r == null) {
            return;
        }
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, r, delay).getTaskId();
    }

    @Override
    public void laterAsync(final Runnable r, final int delay) {
        this.plugin.getServer().getScheduler().runTaskLaterAsynchronously(this.plugin, r, delay);
    }

    @Override
    public void cancel(final int task) {
        if (task != -1) {
            Bukkit.getScheduler().cancelTask(task);
        }
    }
}
