package ru.mirea.delivery.infra.background;

import ru.mirea.delivery.common.Log;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Фоновая работа, повторяемая через заданный интервал. Сбой прохода
 * пишется в журнал и не останавливает повторы; проходы не накладываются
 * друг на друга — следующий начинается после окончания предыдущего.
 */
public final class PeriodicTask implements AutoCloseable {
    private final ScheduledExecutorService executor;

    public PeriodicTask(String name, Duration interval, Runnable work) {
        Log log = Log.of(name);
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                work.run();
            } catch (Throwable error) {
                log.warn("фоновый проход не выполнен", "error", String.valueOf(error.getMessage()));
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
