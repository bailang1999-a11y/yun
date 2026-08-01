package com.xiyiyun.shop.it.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * 真并发执行器。
 *
 * <p>关键点：所有线程先全部就绪（{@code ready} 计数归零），再由主线程一次性放开
 * {@code startGate}，确保它们真的同时冲进被测代码，而不是「串行伪装成并发」。
 */
public final class ConcurrentRunner {

    private ConcurrentRunner() {
    }

    /** 单个任务的执行结果：成功值或异常。 */
    public record Outcome<T>(int index, T value, Throwable error) {
        public boolean succeeded() {
            return error == null;
        }

        public String errorMessage() {
            return error == null ? null : error.getMessage();
        }
    }

    /** 起 {@code threads} 个线程同时执行 {@code taskFactory} 产出的任务，收集每个线程的结果。 */
    public static <T> List<Outcome<T>> runAll(int threads, IntFunction<Callable<T>> taskFactory) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Outcome<T>> outcomes = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                final Callable<T> task = taskFactory.apply(index);
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        startGate.await();
                        T value = task.call();
                        outcomes.add(new Outcome<>(index, value, null));
                    } catch (Throwable error) {
                        outcomes.add(new Outcome<>(index, null, error));
                    } finally {
                        done.countDown();
                    }
                });
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发线程未能在 30s 内全部就绪");
            }
            startGate.countDown();
            if (!done.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发任务未能在 120s 内全部结束，疑似死锁");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发执行被中断", ex);
        } finally {
            pool.shutdownNow();
        }

        List<Outcome<T>> sorted = new ArrayList<>(outcomes);
        sorted.sort((a, b) -> Integer.compare(a.index(), b.index()));
        return List.copyOf(sorted);
    }

    public static <T> long countSucceeded(List<Outcome<T>> outcomes) {
        return outcomes.stream().filter(Outcome::succeeded).count();
    }

    public static <T> List<String> failureMessages(List<Outcome<T>> outcomes) {
        return outcomes.stream()
            .filter(outcome -> !outcome.succeeded())
            .map(outcome -> outcome.error().getClass().getSimpleName() + ": " + outcome.errorMessage())
            .toList();
    }
}
