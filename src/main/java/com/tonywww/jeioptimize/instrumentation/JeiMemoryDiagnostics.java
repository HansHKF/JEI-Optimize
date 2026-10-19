package com.tonywww.jeioptimize.instrumentation;

import com.tonywww.jeioptimize.JeiOptimize;
import com.tonywww.jeioptimize.config.JeiOptFeatureFlags;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Observes the JVM only; no Minecraft objects, allocation stacks, or explicit garbage collection. */
public final class JeiMemoryDiagnostics {
    private static Session active;

    private JeiMemoryDiagnostics() {}

    public static synchronized void begin(long generation) {
        finish("replaced");
        if (!JeiOptFeatureFlags.memoryDiagnostics()) {
            return;
        }
        try {
            Session session = new Session(generation);
            active = session;
            session.timer.scheduleAtFixedRate(session::sample, 250, 250, TimeUnit.MILLISECONDS);
            JeiOptimize.LOGGER.info("JET memory diagnostics started: generation={}, intervalMs=250, scope=whole-jvm, explicitGc=false",
                generation);
        } catch (RuntimeException | LinkageError failure) {
            finish("sampler-failed");
            JeiOptimize.LOGGER.warn("JET memory diagnostics unavailable; JEI startup continues", failure);
        }
    }

    public static synchronized void finish(String outcome) {
        Session session = active;
        active = null;
        if (session == null) {
            return;
        }
        session.timer.shutdownNow();
        try {
            session.report(outcome);
        } catch (RuntimeException | LinkageError failure) {
            JeiOptimize.LOGGER.warn("JET memory diagnostics could not report; JEI behavior is unchanged", failure);
        }
    }

    public static synchronized void finish(long generation, String outcome) {
        if (active != null && active.generation == generation) {
            finish(outcome);
        }
    }

    private static final class Session {
        private final long generation;
        private final long started = System.nanoTime();
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        private final long initialGcCount = gcCount();
        private final long initialGcMillis = gcMillis();
        private final long initialHeapUsed = memory.getHeapMemoryUsage().getUsed();
        private long peakHeapUsed = initialHeapUsed;
        private final ScheduledExecutorService timer;

        private Session(long generation) {
            this.generation = generation;
            timer = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "justenoughthreads-memory");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
        }

        private synchronized void sample() {
            peakHeapUsed = Math.max(peakHeapUsed, memory.getHeapMemoryUsage().getUsed());
        }

        private synchronized void report(String outcome) {
            sample();
            var heap = memory.getHeapMemoryUsage();
            peakHeapUsed = Math.max(peakHeapUsed, heap.getUsed());
            JeiOptimize.LOGGER.info(
                "JET memory diagnostics: generation={}, outcome={}, elapsedMs={}, heapUsedAtStartBytes={}, "
                    + "heapUsedAtEndBytes={}, observedHeapPeakBytes={}, heapCommittedBytes={}, heapMaxBytes={}, "
                    + "nonHeapUsedBytes={}, gcCollections={}, gcCollectionTimeMs={}, scope=whole-jvm, retainedHeap=notMeasured",
                generation, outcome, (System.nanoTime() - started) / 1_000_000L, initialHeapUsed,
                heap.getUsed(), peakHeapUsed, heap.getCommitted(), heap.getMax(), memory.getNonHeapMemoryUsage().getUsed(),
                Math.max(0, gcCount() - initialGcCount), Math.max(0, gcMillis() - initialGcMillis));
        }

        private long gcCount() {
            return collectors.stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
        }

        private long gcMillis() {
            return collectors.stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
        }
    }
}
