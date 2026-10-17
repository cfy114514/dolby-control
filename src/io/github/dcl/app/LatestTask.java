package io.github.dcl.app;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Keeps only the latest pending intent, with its original interaction deadline. */
final class LatestTask<T> {
    interface Action<T> { void run(T value, long deadline); }

    private final Executor executor;
    private final LongSupplier clock;
    private final long lifetimeMillis;
    private final Action<T> action;
    private final Consumer<T> expired;
    private Entry<T> pending;
    private boolean scheduled;

    private static final class Entry<T> {
        final T value;
        final long deadline;
        Entry(T value, long deadline) { this.value = value; this.deadline = deadline; }
    }

    LatestTask(Executor executor, LongSupplier clock, long lifetimeMillis,
               Action<T> action, Consumer<T> expired) {
        if (lifetimeMillis <= 0) throw new IllegalArgumentException("Invalid intent lifetime");
        this.executor = executor;
        this.clock = clock;
        this.lifetimeMillis = lifetimeMillis;
        this.action = action;
        this.expired = expired;
    }

    synchronized void submit(T value) {
        pending = new Entry<>(value, clock.getAsLong() + lifetimeMillis);
        if (!scheduled) schedule();
    }

    private void schedule() {
        scheduled = true;
        try { executor.execute(this::drain); }
        catch (RuntimeException failure) { scheduled = false; pending = null; throw failure; }
    }

    private void drain() {
        boolean finished = false;
        try {
            for (;;) {
                Entry<T> current;
                synchronized (this) {
                    current = pending;
                    pending = null;
                    if (current == null) { scheduled = false; finished = true; return; }
                }
                if (clock.getAsLong() >= current.deadline) expired.accept(current.value);
                else action.run(current.value, current.deadline);
            }
        } finally {
            if (!finished) synchronized (this) {
                scheduled = false;
                if (pending != null) schedule();
            }
        }
    }
}
