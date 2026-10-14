package com.tonywww.jeioptimize.runtime;

import com.tonywww.jeioptimize.instrumentation.JeiOptPhaseTransitions;
import com.tonywww.jeioptimize.instrumentation.JeiOptPhaseTransitions.Sample;

import java.util.List;

/**
 * Checks opt-in phase-transition diagnostics without Minecraft.
 * This does not start a client and does not prove live-frame responsiveness.
 */
public final class TooltipPhaseTransitionTest {
    public static void main(String[] arguments) {
        try {
            verifyDisabledRecordsNothing();
            verifyMilestoneOrder();
            verifyGenerationIsolation();
            verifyCancelDoesNotLeak();
            verifyReconnectResets();
        } finally {
            JeiOptPhaseTransitions.reset();
            JeiOptPhaseTransitions.setEnabledForTest(false);
            JeiOptStartupProgressState.cancel(1);
            JeiOptStartupProgressState.cancel(2);
            JeiOptStartupProgressState.cancel(3);
            JeiOptStartupProgressState.cancel(7);
            JeiOptStartupProgressState.cancel(8);
        }
        System.out.println("TooltipPhaseTransitionTest passed");
    }

    private static void verifyDisabledRecordsNothing() {
        JeiOptPhaseTransitions.reset();
        JeiOptPhaseTransitions.setEnabledForTest(false);
        JeiOptStartupProgressState.begin(1);
        JeiOptStartupProgressState.registerBuild(1, 4, 100);
        JeiOptStartupProgressState.markReady(1);
        JeiOptPhaseTransitions.recordPlugin("Sending Runtime", "kubejs:jei", 4_000_000_000L);
        JeiOptPhaseTransitions.noteClientTick(5_000_000_000L);
        JeiOptPhaseTransitions.noteRenderFrame();
        check(JeiOptPhaseTransitions.snapshotForTest().isEmpty(), "disabled diagnostics store no samples");
        check(JeiOptPhaseTransitions.sessionSnapshotForTest() == null, "disabled diagnostics have no session");
        JeiOptStartupProgressState.markPublished(1);
        JeiOptStartupProgressState.markRuntimeComplete(1);
    }

    private static void verifyMilestoneOrder() {
        JeiOptPhaseTransitions.reset();
        JeiOptPhaseTransitions.setEnabledForTest(true);
        JeiOptStartupProgressState.begin(2);
        JeiOptStartupProgressState.registerBuild(2, 3, 88_000);
        JeiOptStartupProgressState.markReady(2);
        JeiOptPhaseTransitions.onCallbacksStarted(2);
        JeiOptPhaseTransitions.recordPlugin("Sending Runtime", "kubejs:jei", 3_665_000_000L);
        JeiOptPhaseTransitions.onCallbacksCompleted(2, 3_945_000_000L);
        JeiOptPhaseTransitions.onPublished(2);
        JeiOptStartupProgressState.markPublished(2);
        JeiOptStartupProgressState.markRuntimeComplete(2);
        List<String> milestones = JeiOptPhaseTransitions.snapshotForTest().stream()
            .filter(sample -> "milestone".equals(sample.kind()))
            .map(Sample::site)
            .toList();
        check(milestones.get(0).equals("PREPARATION_STARTED"), "preparation starts first: " + milestones);
        check(milestones.contains("PREPARATION_COMPLETED"), "preparation completed");
        check(milestones.contains("INDEXING_STARTED"), "indexing started");
        check(milestones.indexOf("INDEXING_STARTED") > milestones.indexOf("PREPARATION_STARTED"),
            "indexing follows preparation");
        check(milestones.contains("INDEXING_COMPLETED"), "indexing completed");
        check(milestones.contains("RUNTIME_CALLBACKS_STARTED"), "callbacks started");
        check(milestones.contains("RUNTIME_CALLBACKS_COMPLETED"), "callbacks completed");
        check(milestones.contains("RUNTIME_PUBLISHED"), "runtime published");
        check(milestones.contains("FILTER_INDEX_PUBLISHED"), "filter index published");
        check(JeiOptPhaseTransitions.snapshotForTest().stream().anyMatch(sample ->
            "Sending Runtime:kubejs:jei".equals(sample.site()) && sample.durationNanos() == 3_665_000_000L),
            "kubejs sending-runtime duration is retained");
        JeiOptPhaseTransitions.SessionSnapshot session = JeiOptPhaseTransitions.sessionSnapshotForTest();
        check(session != null && session.generation() == 2, "session stays on generation 2");
    }

    private static void verifyGenerationIsolation() {
        JeiOptPhaseTransitions.reset();
        JeiOptPhaseTransitions.setEnabledForTest(true);
        JeiOptStartupProgressState.begin(3);
        JeiOptPhaseTransitions.recordPlugin("Sending Runtime", "old:plugin", 2_000_000_000L);
        JeiOptStartupProgressState.begin(7);
        List<Sample> samples = JeiOptPhaseTransitions.snapshotForTest();
        check(samples.stream().allMatch(sample -> sample.generation() == 7),
            "new generation drops previous samples");
        check(samples.stream().noneMatch(sample -> "Sending Runtime:old:plugin".equals(sample.site())),
            "old generation plugin samples are not retained");
        JeiOptStartupProgressState.cancel(7);
        check(JeiOptPhaseTransitions.sessionSnapshotForTest() == null, "cancel clears the session");
    }

    private static void verifyCancelDoesNotLeak() {
        JeiOptPhaseTransitions.reset();
        JeiOptPhaseTransitions.setEnabledForTest(true);
        JeiOptStartupProgressState.begin(8);
        JeiOptStartupProgressState.registerBuild(8, 2, 10);
        JeiOptStartupProgressState.cancel(8);
        JeiOptPhaseTransitions.SessionSnapshot cancelled = JeiOptPhaseTransitions.sessionSnapshotForTest();
        check(cancelled == null, "cancelled session is not retained");
        JeiOptStartupProgressState.begin(8);
        JeiOptPhaseTransitions.recordPlugin("Sending Runtime", "kubejs:jei", 100_000_000L);
        check(JeiOptPhaseTransitions.snapshotForTest().stream().allMatch(sample -> sample.generation() == 8),
            "reconnect generation 8 only contains new samples");
        check(JeiOptPhaseTransitions.snapshotForTest().stream().noneMatch(Sample::cancelled),
            "new generation samples are not marked cancelled");
    }

    private static void verifyReconnectResets() {
        JeiOptPhaseTransitions.reset();
        JeiOptPhaseTransitions.setEnabledForTest(true);
        JeiOptStartupProgressState.begin(8);
        JeiOptPhaseTransitions.noteClientTick(4_902_000_000L);
        JeiOptPhaseTransitions.SessionSnapshot first = JeiOptPhaseTransitions.sessionSnapshotForTest();
        check(first != null && first.maxClientTickNanos() == 4_902_000_000L, "tick stall recorded");
        JeiOptStartupProgressState.cancel(8);
        JeiOptStartupProgressState.begin(9);
        JeiOptPhaseTransitions.SessionSnapshot second = JeiOptPhaseTransitions.sessionSnapshotForTest();
        check(second != null && second.generation() == 9, "reconnect starts generation 9");
        check(second.maxClientTickNanos() == 0L, "tick totals do not leak across generations");
        JeiOptStartupProgressState.cancel(9);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
