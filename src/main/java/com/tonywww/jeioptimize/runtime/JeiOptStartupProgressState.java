package com.tonywww.jeioptimize.runtime;

import com.tonywww.jeioptimize.instrumentation.JeiOptPhaseTransitions;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class JeiOptStartupProgressState {
    private static final Object LOCK = new Object();
    private static final CompletableFuture<Void> ALREADY_PUBLISHED = CompletableFuture.completedFuture(null);

    private static long generation = Long.MIN_VALUE;
    private static Stage stage = Stage.HIDDEN;
    private static int completedChunks;
    private static int totalChunks;
    private static int totalIngredients;
    private static boolean buildRegistered;
    private static boolean runtimeComplete;
    private static CompletableFuture<Void> publication = ALREADY_PUBLISHED;

    private JeiOptStartupProgressState() {
    }

    public static void begin(long newGeneration) {
        CompletableFuture<Void> previous;
        synchronized (LOCK) {
            previous = publication;
            generation = newGeneration;
            stage = Stage.PREPARING;
            completedChunks = 0;
            totalChunks = 0;
            totalIngredients = 0;
            buildRegistered = false;
            runtimeComplete = false;
            publication = new CompletableFuture<>();
        }
        if (!previous.isDone()) {
            previous.cancel(false);
        }
        JeiOptPhaseTransitions.onBegin(newGeneration);
    }

    public static boolean registerBuild(long expectedGeneration, int chunks, int ingredients) {
        boolean registered;
        synchronized (LOCK) {
            if (generation != expectedGeneration || stage == Stage.CANCELLED || stage == Stage.HIDDEN) {
                return false;
            }
            buildRegistered = true;
            completedChunks = 0;
            totalChunks = Math.max(0, chunks);
            totalIngredients = Math.max(0, ingredients);
            stage = totalChunks == 0 ? Stage.READY : Stage.INDEXING;
            registered = true;
        }
        JeiOptPhaseTransitions.onIndexingStarted(expectedGeneration, chunks, ingredients);
        return registered;
    }

    public static void markChunkCompleted(long expectedGeneration) {
        synchronized (LOCK) {
            if (generation != expectedGeneration || !buildRegistered || stage != Stage.INDEXING) {
                return;
            }
            completedChunks = Math.min(totalChunks, completedChunks + 1);
        }
    }

    public static void markReady(long expectedGeneration) {
        boolean completed;
        synchronized (LOCK) {
            if (generation != expectedGeneration || !buildRegistered
                || (stage != Stage.INDEXING && stage != Stage.READY)) {
                return;
            }
            completedChunks = totalChunks;
            stage = Stage.READY;
            completed = true;
        }
        if (completed) {
            JeiOptPhaseTransitions.onIndexingCompleted(expectedGeneration);
        }
    }

    public static CompletableFuture<Void> publicationFuture(long expectedGeneration) {
        synchronized (LOCK) {
            if (generation != expectedGeneration || !buildRegistered) {
                return ALREADY_PUBLISHED;
            }
            return publication;
        }
    }

    /** Releases runtime publication waiters even if building, sealing, or installing the filter fails. */
    public static void trackPublication(long expectedGeneration, CompletableFuture<?> filterPublication) {
        filterPublication.whenComplete((ignored, failure) -> {
            if (failure == null) {
                markPublished(expectedGeneration);
            } else {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                fail(expectedGeneration, cause);
            }
        });
    }

    public static void markPublished(long expectedGeneration) {
        CompletableFuture<Void> toComplete;
        synchronized (LOCK) {
            if (generation != expectedGeneration || !buildRegistered
                || stage == Stage.CANCELLED || stage == Stage.HIDDEN) {
                return;
            }
            completedChunks = totalChunks;
            stage = runtimeComplete ? Stage.HIDDEN : Stage.PUBLISHED;
            toComplete = publication;
        }
        toComplete.complete(null);
        JeiOptPhaseTransitions.onFilterIndexPublished(expectedGeneration);
    }

    public static void markRuntimeComplete(long expectedGeneration) {
        CompletableFuture<Void> toComplete = null;
        synchronized (LOCK) {
            if (generation != expectedGeneration || stage == Stage.CANCELLED || stage == Stage.HIDDEN) {
                return;
            }
            runtimeComplete = true;
            if (!buildRegistered) {
                stage = Stage.HIDDEN;
                toComplete = publication;
            } else if (stage == Stage.PUBLISHED) {
                stage = Stage.HIDDEN;
            }
        }
        if (toComplete != null) {
            toComplete.complete(null);
        }
        JeiOptPhaseTransitions.onRuntimeComplete(expectedGeneration);
    }

    public static void fail(long expectedGeneration, Throwable error) {
        CompletableFuture<Void> toFail;
        synchronized (LOCK) {
            if (generation != expectedGeneration || stage == Stage.CANCELLED || stage == Stage.HIDDEN) {
                return;
            }
            stage = Stage.HIDDEN;
            toFail = publication;
        }
        toFail.completeExceptionally(error);
        JeiOptPhaseTransitions.onFail(expectedGeneration, error);
    }

    public static void cancel(long expectedGeneration) {
        CompletableFuture<Void> toCancel;
        synchronized (LOCK) {
            if (generation != expectedGeneration) {
                return;
            }
            stage = Stage.CANCELLED;
            completedChunks = 0;
            totalChunks = 0;
            totalIngredients = 0;
            buildRegistered = false;
            runtimeComplete = false;
            toCancel = publication;
        }
        if (!toCancel.isDone()) {
            toCancel.cancel(false);
        }
        JeiOptPhaseTransitions.onCancel(expectedGeneration);
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(stage, completedChunks, totalChunks, totalIngredients);
        }
    }

    public static boolean blocksJeiInput() {
        synchronized (LOCK) {
            return stage == Stage.PREPARING
                || stage == Stage.INDEXING
                || stage == Stage.READY
                || stage == Stage.PUBLISHED;
        }
    }

    public static boolean blocksJeiRendering(boolean runtimeAvailable) {
        return !runtimeAvailable || blocksJeiInput();
    }

    public enum Stage {
        HIDDEN,
        PREPARING,
        INDEXING,
        READY,
        PUBLISHED,
        CANCELLED
    }

    public record Snapshot(Stage stage, int completedChunks, int totalChunks, int totalIngredients) {
        public boolean visible() {
            return stage != Stage.HIDDEN && stage != Stage.CANCELLED;
        }

        public double fraction() {
            if (totalChunks <= 0) {
                return stage == Stage.READY || stage == Stage.PUBLISHED ? 1.0D : 0.0D;
            }
            return Math.min(1.0D, Math.max(0.0D, (double) completedChunks / totalChunks));
        }
    }
}
