package com.tonywww.jeioptimize.instrumentation;

import com.tonywww.jeioptimize.JeiOptimize;
import com.tonywww.jeioptimize.config.JeiOptFeatureFlags;
import com.tonywww.jeioptimize.runtime.JeiOptStartupProgressState;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in recorder for JEI startup phase transitions A → B (Preparing → Indexing)
 * and B → C (Indexing completed → overlay unblocked).
 *
 * <p>Disabled by default. When off, every public method returns after a boolean check
 * and allocates nothing. This class does not change JEI call order, threads, or publication.
 *
 * <p>Instrumentation map:
 * <ul>
 *   <li>{@code PREPARATION_STARTED} - {@code JeiOptStartupProgressState.begin}</li>
 *   <li>{@code PREPARATION_COMPLETED} / {@code INDEXING_STARTED} - {@code registerBuild}</li>
 *   <li>{@code INDEXING_COMPLETED} - {@code markReady}</li>
 *   <li>{@code RUNTIME_CALLBACKS_STARTED/COMPLETED} - {@code JeiOptRuntimePublication.runCallbacksAndPublish}</li>
 *   <li>{@code RUNTIME_PUBLISHED} - {@code Internal.setRuntime} path after callbacks</li>
 *   <li>{@code OVERLAY_UNBLOCKED} - first {@code Minecraft.runTick} after {@code blocksJeiInput()} is false</li>
 *   <li>plugin operations - {@code JeiOptDiagnostics.callPluginWithTiming}</li>
 *   <li>render-frame gaps - {@code ClientTickHookMixin} {@code runTick} HEAD</li>
 *   <li>client-tick duration - {@code ClientTickHookMixin} {@code tick} TAIL</li>
 * </ul>
 */
public final class JeiOptPhaseTransitions {
    private static final Object LOCK = new Object();
    private static final int MAX_SAMPLES = 128;
    private static final long FRAME_STALL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long TICK_STALL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long JFR_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long LOG_STALL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private static volatile boolean testEnabled;
    private static Session session;

    private JeiOptPhaseTransitions() {
    }

    public static boolean enabled() {
        return testEnabled || JeiOptFeatureFlags.phaseTransitions();
    }

    public static void setEnabledForTest(boolean enabled) {
        testEnabled = enabled;
        if (!enabled) {
            reset();
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            session = null;
        }
    }

    public static void onBegin(long generation) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            if (session != null && session.generation != generation && !session.summarized) {
                logSummary(session, "superseded");
            }
            session = new Session(generation);
            add(session, Sample.milestone(generation, "PREPARING", "PREPARATION_STARTED", siteThread()));
        }
    }

    public static void onIndexingStarted(long generation, int chunks, int ingredients) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            Session current = require(generation);
            if (current == null) {
                return;
            }
            current.phase = "INDEXING";
            current.chunks = chunks;
            current.ingredients = ingredients;
            add(current, Sample.milestone(generation, "INDEXING", "PREPARATION_COMPLETED", siteThread()));
            add(current, Sample.milestone(generation, "INDEXING", "INDEXING_STARTED", siteThread()));
        }
    }

    public static void onIndexingCompleted(long generation) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            Session current = require(generation);
            if (current == null) {
                return;
            }
            current.phase = "READY";
            add(current, Sample.milestone(generation, "READY", "INDEXING_COMPLETED", siteThread()));
        }
    }

    public static void onFilterIndexPublished(long generation) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            Session current = require(generation);
            if (current == null) {
                return;
            }
            add(current, Sample.milestone(generation, current.phase, "FILTER_INDEX_PUBLISHED", siteThread()));
        }
    }

    public static void onPublished(long generation) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            Session current = require(generation);
            if (current == null) {
                return;
            }
            current.phase = "PUBLISHED";
            add(current, Sample.milestone(generation, "PUBLISHED", "RUNTIME_PUBLISHED", siteThread()));
        }
    }

    public static void onRuntimeComplete(long generation) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            Session current = require(generation);
            if (current == null) {
                return;
            }
            current.runtimeComplete = true;
        }
    }

    public static void onCancel(long generation) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            Session current = require(generation);
            if (current == null) {
                return;
            }
            current.cancelled = true;
            current.phase = "CANCELLED";
            add(current, Sample.milestone(generation, "CANCELLED", "CANCELLED", siteThread()));
            if (!current.summarized) {
                logSummary(current, "cancelled");
                current.summarized = true;
            }
            if (session == current) {
                session = null;
            }
        }
    }

    public static void onFail(long generation, Throwable error) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            Session current = require(generation);
            if (current == null) {
                return;
            }
            current.fallback = true;
            current.phase = "FAILED";
            String site = error == null ? "fail" : error.getClass().getSimpleName();
            add(current, Sample.operation(generation, "FAILED", site, siteThread(), 0L, true, true));
            if (!current.summarized) {
                logSummary(current, "failed");
                current.summarized = true;
            }
            if (session == current) {
                session = null;
            }
        }
    }

    public static void onCallbacksStarted(long generation) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            Session current = require(generation);
            if (current == null) {
                return;
            }
            current.phase = "CALLBACKS";
            add(current, Sample.milestone(generation, "CALLBACKS", "RUNTIME_CALLBACKS_STARTED", siteThread()));
        }
    }

    public static void onCallbacksCompleted(long generation, long durationNanos) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            Session current = require(generation);
            if (current == null) {
                return;
            }
            add(current, Sample.operation(
                generation, "CALLBACKS", "runtimeCallbacks", siteThread(), durationNanos, false, false));
            add(current, Sample.milestone(generation, "CALLBACKS", "RUNTIME_CALLBACKS_COMPLETED", siteThread()));
        }
        maybeJfr("runtimeCallbacks", "CALLBACKS", generation, durationNanos);
    }

    public static void measure(String site, Runnable action) {
        if (!enabled()) {
            action.run();
            return;
        }
        long started = System.nanoTime();
        try {
            action.run();
        } finally {
            recordOperation(site, System.nanoTime() - started, false);
        }
    }

    public static void recordPlugin(String title, String pluginUid, long durationNanos) {
        if (!enabled()) {
            return;
        }
        String site = title + ":" + pluginUid;
        recordSite(site, durationNanos);
    }

    public static void recordSite(String site, long durationNanos) {
        if (!enabled()) {
            return;
        }
        recordOperation(site, durationNanos, false);
        maybeJfr(site, currentPhase(), currentGeneration(), durationNanos);
    }

    public static void recordFilterSlice(long durationNanos) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            if (session == null) {
                return;
            }
            session.filterSlices++;
            session.maxFilterSliceNanos = Math.max(session.maxFilterSliceNanos, durationNanos);
        }
    }

    public static void noteClientTick(long durationNanos) {
        if (!enabled()) {
            return;
        }
        synchronized (LOCK) {
            if (session == null) {
                return;
            }
            session.clientTicks++;
            session.totalClientTickNanos += durationNanos;
            session.maxClientTickNanos = Math.max(session.maxClientTickNanos, durationNanos);
            if (durationNanos >= TICK_STALL_NANOS) {
                session.clientTickStallCount++;
                add(session, Sample.stall(
                    session.generation,
                    session.phase,
                    "clientTick",
                    "Minecraft.tick",
                    durationNanos,
                    session.cancelled,
                    session.fallback));
            }
        }
    }

    public static void noteRenderFrame() {
        if (!enabled()) {
            return;
        }
        long now = System.nanoTime();
        boolean overlayUnblocked = false;
        synchronized (LOCK) {
            if (session == null) {
                return;
            }
            Session current = session;
            if (current.lastFrameNanos != 0L) {
                long gap = now - current.lastFrameNanos;
                current.frames++;
                current.totalFrameNanos += gap;
                current.maxFrameGapNanos = Math.max(current.maxFrameGapNanos, gap);
                if (gap >= FRAME_STALL_NANOS) {
                    current.frameStallCount++;
                    add(current, Sample.stall(
                        current.generation,
                        current.phase,
                        "renderFrameGap",
                        "Minecraft.runTick",
                        gap,
                        current.cancelled,
                        current.fallback));
                }
            }
            current.lastFrameNanos = now;
            if (!current.overlayUnblocked && !JeiOptStartupProgressState.blocksJeiInput()) {
                current.overlayUnblocked = true;
                current.phase = "OPERATIONAL";
                add(current, Sample.milestone(current.generation, "OPERATIONAL", "OVERLAY_UNBLOCKED", siteThread()));
                overlayUnblocked = true;
                if (!current.summarized) {
                    logSummary(current, "overlay-unblocked");
                    current.summarized = true;
                }
            }
        }
        if (overlayUnblocked) {
            JeiOptimize.LOGGER.info(
                "JEI phase-transition overlay unblocked: generation={}, site=Minecraft.runTick after blocksJeiInput=false",
                currentGeneration());
        }
    }

    public static List<Sample> snapshotForTest() {
        synchronized (LOCK) {
            if (session == null) {
                return List.of();
            }
            return List.copyOf(session.samples);
        }
    }

    public static SessionSnapshot sessionSnapshotForTest() {
        synchronized (LOCK) {
            if (session == null) {
                return null;
            }
            return new SessionSnapshot(
                session.generation,
                session.phase,
                session.cancelled,
                session.fallback,
                session.summarized,
                session.overlayUnblocked,
                session.maxClientTickNanos,
                session.maxFrameGapNanos,
                session.samples.size()
            );
        }
    }

    private static void recordOperation(String site, long durationNanos, boolean fallback) {
        synchronized (LOCK) {
            if (session == null) {
                return;
            }
            add(session, Sample.operation(
                session.generation,
                session.phase,
                site,
                siteThread(),
                durationNanos,
                session.cancelled,
                session.fallback || fallback));
            if (durationNanos >= session.maxOperationNanos) {
                session.maxOperationNanos = durationNanos;
                session.maxOperationSite = site;
            }
        }
        if (durationNanos >= LOG_STALL_NANOS) {
            JeiOptimize.LOGGER.info(
                "JEI phase-transition stall: generation={}, phase={}, thread={}, site={}, durationMs={}, cancelled={}, fallback={}",
                currentGeneration(),
                currentPhase(),
                siteThread(),
                site,
                durationNanos / 1_000_000L,
                false,
                fallback
            );
        }
        maybeJfr(site, currentPhase(), currentGeneration(), durationNanos);
    }

    private static void maybeJfr(String site, String phase, long generation, long durationNanos) {
        if (durationNanos < JFR_NANOS) {
            return;
        }
        StallEvent event = new StallEvent();
        event.site = site;
        event.phase = phase;
        event.generation = generation;
        event.durationNanos = durationNanos;
        event.commit();
    }

    private static Session require(long generation) {
        if (session == null || session.generation != generation) {
            return null;
        }
        return session;
    }

    private static long currentGeneration() {
        Session current = session;
        return current == null ? Long.MIN_VALUE : current.generation;
    }

    private static String currentPhase() {
        Session current = session;
        return current == null ? "NONE" : current.phase;
    }

    private static String siteThread() {
        return Thread.currentThread().getName();
    }

    private static void add(Session current, Sample sample) {
        if ("milestone".equals(sample.kind)) {
            current.samples.add(sample);
            return;
        }
        if (sample.durationNanos < TimeUnit.MILLISECONDS.toNanos(1) && !"stall".equals(sample.kind)) {
            return;
        }
        if (current.samples.size() < MAX_SAMPLES) {
            current.samples.add(sample);
            return;
        }
        int shortest = -1;
        long shortestDuration = sample.durationNanos;
        for (int index = 0; index < current.samples.size(); index++) {
            Sample existing = current.samples.get(index);
            if ("milestone".equals(existing.kind)) {
                continue;
            }
            if (existing.durationNanos < shortestDuration) {
                shortestDuration = existing.durationNanos;
                shortest = index;
            }
        }
        if (shortest < 0) {
            current.dropped++;
            return;
        }
        current.samples.remove(shortest);
        current.samples.add(sample);
        current.dropped++;
    }

    private static void logSummary(Session current, String reason) {
        StringBuilder top = new StringBuilder();
        current.samples.stream()
            .filter(sample -> "operation".equals(sample.kind) || "stall".equals(sample.kind))
            .sorted((left, right) -> Long.compare(right.durationNanos, left.durationNanos))
            .limit(8)
            .forEach(sample -> {
                if (top.length() > 0) {
                    top.append("; ");
                }
                top.append(sample.site)
                    .append('=')
                    .append(sample.durationNanos / 1_000_000L)
                    .append("ms@")
                    .append(sample.thread)
                    .append('/')
                    .append(sample.phase);
            });
        long prep = timestamp(current, "PREPARATION_STARTED");
        long indexStart = timestamp(current, "INDEXING_STARTED");
        long indexDone = timestamp(current, "INDEXING_COMPLETED");
        long callbacksStart = timestamp(current, "RUNTIME_CALLBACKS_STARTED");
        long callbacksDone = timestamp(current, "RUNTIME_CALLBACKS_COMPLETED");
        long published = timestamp(current, "RUNTIME_PUBLISHED");
        long overlay = timestamp(current, "OVERLAY_UNBLOCKED");
        JeiOptimize.LOGGER.info(
            "JEI phase-transition summary: generation={}, reason={}, cancelled={}, fallback={}, "
                + "ingredients={}, chunks={}, "
                + "prepToIndexMs={}, indexBuildMs={}, indexToCallbacksMs={}, callbacksMs={}, publishToOverlayMs={}, "
                + "maxClientTickMs={}, maxRenderFrameGapMs={}, clientTickStalls={}, renderFrameStalls={}, "
                + "filterSlices={}, maxFilterSliceMs={}, maxOperation={}ms@{}, dropped={}, top=[{}]",
            current.generation,
            reason,
            current.cancelled,
            current.fallback,
            current.ingredients,
            current.chunks,
            deltaMs(prep, indexStart),
            deltaMs(indexStart, indexDone),
            deltaMs(indexDone, callbacksStart),
            deltaMs(callbacksStart, callbacksDone),
            deltaMs(published, overlay),
            current.maxClientTickNanos / 1_000_000L,
            current.maxFrameGapNanos / 1_000_000L,
            current.clientTickStallCount,
            current.frameStallCount,
            current.filterSlices,
            current.maxFilterSliceNanos / 1_000_000L,
            current.maxOperationNanos / 1_000_000L,
            current.maxOperationSite,
            current.dropped,
            top
        );
    }

    private static long timestamp(Session current, String site) {
        for (Sample sample : current.samples) {
            if ("milestone".equals(sample.kind) && site.equals(sample.site)) {
                return sample.monotonicNanos;
            }
        }
        return 0L;
    }

    private static long deltaMs(long start, long end) {
        if (start == 0L || end == 0L || end < start) {
            return -1L;
        }
        return (end - start) / 1_000_000L;
    }

    public record Sample(
        long generation,
        String kind,
        String phase,
        String thread,
        long monotonicNanos,
        long durationNanos,
        String site,
        boolean cancelled,
        boolean fallback
    ) {
        private static Sample milestone(long generation, String phase, String site, String thread) {
            return new Sample(generation, "milestone", phase, thread, System.nanoTime(), 0L, site, false, false);
        }

        private static Sample operation(
            long generation,
            String phase,
            String site,
            String thread,
            long durationNanos,
            boolean cancelled,
            boolean fallback
        ) {
            return new Sample(
                generation, "operation", phase, thread, System.nanoTime(), durationNanos, site, cancelled, fallback);
        }

        private static Sample stall(
            long generation,
            String phase,
            String site,
            String callSite,
            long durationNanos,
            boolean cancelled,
            boolean fallback
        ) {
            return new Sample(
                generation, "stall", phase, siteThread(), System.nanoTime(), durationNanos, callSite + "/" + site,
                cancelled, fallback);
        }
    }

    public record SessionSnapshot(
        long generation,
        String phase,
        boolean cancelled,
        boolean fallback,
        boolean summarized,
        boolean overlayUnblocked,
        long maxClientTickNanos,
        long maxFrameGapNanos,
        int sampleCount
    ) {
    }

    private static final class Session {
        private final long generation;
        private final List<Sample> samples = new ArrayList<>();
        private String phase = "PREPARING";
        private boolean cancelled;
        private boolean fallback;
        private boolean summarized;
        private boolean overlayUnblocked;
        private boolean runtimeComplete;
        private int chunks;
        private int ingredients;
        private int dropped;
        private int frames;
        private int clientTicks;
        private int frameStallCount;
        private int clientTickStallCount;
        private int filterSlices;
        private long lastFrameNanos;
        private long totalFrameNanos;
        private long maxFrameGapNanos;
        private long totalClientTickNanos;
        private long maxClientTickNanos;
        private long maxFilterSliceNanos;
        private long maxOperationNanos;
        private String maxOperationSite = "none";

        private Session(long generation) {
            this.generation = generation;
        }
    }

    @Name("jet.PhaseStall")
    @StackTrace(true)
    public static final class StallEvent extends Event {
        public String site;
        public String phase;
        public long generation;
        public long durationNanos;
    }
}
