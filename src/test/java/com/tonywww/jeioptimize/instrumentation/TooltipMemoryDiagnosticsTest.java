package com.tonywww.jeioptimize.instrumentation;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Exercises a late completion from an obsolete startup and sampler termination. */
public final class TooltipMemoryDiagnosticsTest {
    public static void main(String[] args) throws Exception {
        Class<?> sessionType = Class.forName(JeiMemoryDiagnostics.class.getName() + "$Session");
        var constructor = sessionType.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        Field active = JeiMemoryDiagnostics.class.getDeclaredField("active");
        active.setAccessible(true);
        Field timer = sessionType.getDeclaredField("timer");
        timer.setAccessible(true);
        Object first = constructor.newInstance(1L);
        Object second = constructor.newInstance(2L);
        ScheduledExecutorService firstTimer = (ScheduledExecutorService) timer.get(first);
        ScheduledExecutorService secondTimer = (ScheduledExecutorService) timer.get(second);
        try {
            CountDownLatch sampled = new CountDownLatch(1);
            secondTimer.execute(sampled::countDown);
            check(sampled.await(2, TimeUnit.SECONDS), "sampler starts without a game or world reference");
            active.set(null, second);
            JeiMemoryDiagnostics.finish(1, "obsolete-startup-returned");
            check(active.get(null) == second && !secondTimer.isShutdown(),
                "an obsolete startup must not end the new generation's sampler");
            JeiMemoryDiagnostics.finish(2, "started");
            check(active.get(null) == null, "completion releases the session");
            check(secondTimer.isShutdown() && secondTimer.awaitTermination(2, TimeUnit.SECONDS),
                "completion stops the diagnostic thread");
            active.set(null, first);
            JeiMemoryDiagnostics.finish("cancelled");
            check(active.get(null) == null && firstTimer.isShutdown(),
                "world cancellation releases the session and stops its timer");
            JeiMemoryDiagnostics.finish("already-stopped");
        } finally {
            active.set(null, null);
            firstTimer.shutdownNow();
            secondTimer.shutdownNow();
        }
        System.out.println("TooltipMemoryDiagnosticsTest passed: stale completion, cancellation and thread cleanup");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
