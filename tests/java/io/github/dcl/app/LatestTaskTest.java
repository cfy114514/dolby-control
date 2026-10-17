package io.github.dcl.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Exercises the production hook scheduler without Android, Root, or a phone. */
public final class LatestTaskTest {
    private static int passed;

    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        boolean rejected;
        @Override public void execute(Runnable job) {
            if (rejected) throw new RejectedExecutionException();
            jobs.add(job);
        }
        void next() { jobs.remove().run(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void check(String name, Runnable test) {
        test.run(); passed++; System.out.println("PASS " + name);
    }

    public static void main(String[] args) {
        check("rapid selections keep one queued job and the final native profile", () -> {
            Queue queue = new Queue();
            List<Integer> calls = new ArrayList<>();
            LatestTask<Integer> tasks = new LatestTask<>(queue, () -> 0, 20000,
                    (value, deadline) -> calls.add(value), ignored -> { throw new AssertionError(); });
            for (int i = 0; i < 10000; i++) tasks.submit(i % 4);
            require(queue.jobs.size() == 1, "Unbounded executor queue");
            queue.next();
            require(calls.equals(Arrays.asList(3)), "Replayed superseded native selections: " + calls);
        });
        check("while one RPC runs only the newest Spatial Audio intent remains", () -> {
            Queue queue = new Queue();
            List<Integer> calls = new ArrayList<>();
            List<LatestTask<Integer>> ref = new ArrayList<>();
            LatestTask<Integer> tasks = new LatestTask<>(queue, () -> 0, 20000, (value, deadline) -> {
                calls.add(value);
                if (value == 0) { ref.get(0).submit(1); ref.get(0).submit(2); ref.get(0).submit(8); }
                require(queue.jobs.isEmpty(), "Appended a job per click");
            }, ignored -> { throw new AssertionError(); });
            ref.add(tasks); tasks.submit(0); queue.next();
            require(calls.equals(Arrays.asList(0, 8)), "Lost the final Spatial Audio request: " + calls);
        });
        check("queued work retains its original deadline", () -> {
            Queue queue = new Queue(); AtomicLong clock = new AtomicLong(100);
            List<Long> deadlines = new ArrayList<>();
            LatestTask<Integer> tasks = new LatestTask<>(queue, clock::get, 20000,
                    (value, deadline) -> deadlines.add(deadline), ignored -> { throw new AssertionError(); });
            tasks.submit(2); clock.set(19000); queue.next();
            require(deadlines.equals(Arrays.asList(20100L)), "Queue reset the interaction deadline");
        });
        check("expired latest intent is reported without a late mutation", () -> {
            Queue queue = new Queue(); AtomicLong clock = new AtomicLong();
            List<Integer> calls = new ArrayList<>(), expired = new ArrayList<>();
            LatestTask<Integer> tasks = new LatestTask<>(queue, clock::get, 20000,
                    (value, deadline) -> calls.add(value), expired::add);
            tasks.submit(1); tasks.submit(8); clock.set(20000); queue.next();
            require(calls.isEmpty() && expired.equals(Arrays.asList(8)), "Expired work changed the mode");
            tasks.submit(2); queue.next();
            require(calls.equals(Arrays.asList(2)), "A fresh retry could not execute");
        });
        check("a failed RPC cannot strand the next pending selection", () -> {
            Queue queue = new Queue(); List<Integer> calls = new ArrayList<>();
            List<LatestTask<Integer>> ref = new ArrayList<>();
            LatestTask<Integer> tasks = new LatestTask<>(queue, () -> 0, 20000, (value, deadline) -> {
                calls.add(value);
                if (value == 0) { ref.get(0).submit(3); throw new IllegalStateException("fixture"); }
            }, ignored -> { });
            ref.add(tasks); tasks.submit(0);
            try { queue.next(); throw new AssertionError("Missing fixture failure"); }
            catch (IllegalStateException expected) { }
            require(queue.jobs.size() == 1, "Pending choice was stranded"); queue.next();
            require(calls.equals(Arrays.asList(0, 3)), "Lost the newest request after failure");
        });
        check("an executor rejection does not poison a later retry", () -> {
            Queue queue = new Queue(); List<Integer> calls = new ArrayList<>();
            LatestTask<Integer> tasks = new LatestTask<>(queue, () -> 0, 20000,
                    (value, deadline) -> calls.add(value), ignored -> { });
            queue.rejected = true;
            try { tasks.submit(8); throw new AssertionError("Missing rejection"); }
            catch (RejectedExecutionException expected) { }
            queue.rejected = false; tasks.submit(2); queue.next();
            require(calls.equals(Arrays.asList(2)), "Rejected request was replayed");
        });
        check("manual coalescing and a restore share the serialized worker", () -> {
            Queue queue = new Queue(); List<Integer> calls = new ArrayList<>();
            LatestTask<Integer> manual = new LatestTask<>(queue, () -> 0, 20000,
                    (value, deadline) -> calls.add(value), ignored -> { });
            LatestTask<Boolean> restore = new LatestTask<>(queue, () -> 0, 20000,
                    (value, deadline) -> calls.add(-1), ignored -> { });
            manual.submit(2);
            for (int i = 0; i < 1000; i++) restore.submit(true);
            manual.submit(8);
            require(queue.jobs.size() == 2, "Restore bypassed coalescing");
            queue.next(); queue.next();
            require(calls.equals(Arrays.asList(8, -1)), "Restore ran before the newest manual choice");
        });
        System.out.println(passed + " hook queue tests passed");
    }
}
