package io.github.dcl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Host tests execute production classes, including real child processes and real file locks. */
public final class BackendRuntimeTest {
    private static int passed;
    private interface Check { void run() throws Exception; }

    private static void check(String name, Check operation) throws Exception {
        operation.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Check operation, String part) throws Exception {
        try { operation.run(); }
        catch (Exception error) {
            require(error.toString().contains(part), "Unexpected failure: " + error);
            return;
        }
        throw new AssertionError("Expected failure containing " + part);
    }

    private static List<String> child(String mode) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Arrays.asList(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), BackendRuntimeTest.class.getName(), mode);
    }

    private static void childMain(String mode) throws Exception {
        switch (mode) {
            case "echo": System.out.print("first"); System.err.print("second"); return;
            case "environment": System.out.print(System.getenv("DCL_EXPECT_PROCESS")); return;
            case "fail": System.err.print("fixture-failure"); System.exit(7); return;
            case "flood":
                byte[] block = new byte[8192];
                Arrays.fill(block, (byte) 'x');
                for (int i = 0; i < 1000; i++) System.out.write(block);
                return;
            case "hang":
                for (;;) { System.out.print("x"); System.out.flush(); Thread.sleep(20); }
            default: throw new IllegalArgumentException(mode);
        }
    }

    private static void processes() throws Exception {
        check("child stdout and stderr are drained", () -> {
            String value = BoundedProcess.run(child("echo"), null, 5000, 1024);
            require(value.contains("first") && value.contains("second"), value);
        });
        check("child expected identity is retained", () ->
                require("123:456:boot".equals(BoundedProcess.run(child("environment"), "123:456:boot", 5000, 1024)), "Missing identity"));
        check("nonzero child failure is reported", () ->
                expectFailure(() -> BoundedProcess.run(child("fail"), null, 5000, 1024), "fixture-failure"));
        check("oversize child is stopped", () ->
                expectFailure(() -> BoundedProcess.run(child("flood"), null, 5000, 4096), "Oversize command output"));
        check("continuously writing child still has a total deadline", () -> {
            long started = System.nanoTime();
            expectFailure(() -> BoundedProcess.run(child("hang"), null, 300, 100000), "timed out");
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000, "Child hung past deadline");
        });
        check("timeout confirms child termination before returning", () -> {
            Path pidFile = Files.createTempFile("dcl-child-", ".pid");
            try {
                List<String> arguments = new ArrayList<>(child("pid-hang"));
                arguments.add(pidFile.toString());
                expectFailure(() -> BoundedProcess.run(arguments, null, 1500, 100000), "timed out");
                long pid = Long.parseLong(Files.readString(pidFile));
                require(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false,
                        "Child remained alive when recovery could begin");
            } finally { Files.deleteIfExists(pidFile); }
        });
    }

    private static void frames() throws Exception {
        check("UTF-8 control frame stops at newline", () -> {
            List<Integer> limits = new ArrayList<>();
            String line = BoundedLine.read(new ByteArrayInputStream("声音\nignored".getBytes(StandardCharsets.UTF_8)), 32, 1000, limits::add);
            require("声音".equals(line), line);
            require(!limits.isEmpty() && limits.get(0) > 0 && limits.get(0) <= 1000, "Socket deadline missing");
        });
        check("control frame size is bounded", () ->
                expectFailure(() -> BoundedLine.read(new ByteArrayInputStream("1234\n".getBytes(StandardCharsets.UTF_8)), 3, 1000, ignored -> {}), "too large"));
        check("truncated control frame is rejected", () ->
                expectFailure(() -> BoundedLine.read(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), 32, 1000, ignored -> {}), "Incomplete"));
        check("trickled control frame has a total deadline", () -> {
            InputStream trickle = new InputStream() {
                @Override public int read() { return 'x'; }
                @Override public int read(byte[] bytes, int start, int length) throws IOException {
                    try { Thread.sleep(40); }
                    catch (InterruptedException error) { throw new IOException(error); }
                    bytes[start] = 'x';
                    return 1;
                }
            };
            long started = System.nanoTime();
            expectFailure(() -> BoundedLine.read(trickle, 32, 70, ignored -> {}), "timed out");
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "Trickle held reader");
        });
    }

    private static void requests() throws Exception {
        check("socket queue time cannot revive an expired mutation", () -> {
            AtomicInteger writes = new AtomicInteger();
            RequestTask<Integer> request = new RequestTask<>(writes::incrementAndGet, 10, () -> 20);
            request.run();
            try { request.await(1, TimeUnit.SECONDS); throw new AssertionError("Expired mutation ran"); }
            catch (ExecutionException error) {
                require(error.getCause() instanceof TimeoutException, error.toString());
            }
            require(writes.get() == 0, "Expired socket-pool request mutated audio");
        });
        check("expired queued mutation never executes later", () -> {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger writes = new AtomicInteger();
            try {
                executor.execute(() -> { try { release.await(); } catch (InterruptedException error) { throw new AssertionError(error); } });
                RequestTask<Integer> request = new RequestTask<>(writes::incrementAndGet);
                executor.execute(request);
                try { request.await(30, TimeUnit.MILLISECONDS); throw new AssertionError("Missing timeout"); }
                catch (TimeoutException expected) {}
                release.countDown();
                executor.shutdown();
                require(executor.awaitTermination(2, TimeUnit.SECONDS), "Queue did not finish");
                require(writes.get() == 0, "Expired request mutated later");
            } finally { release.countDown(); executor.shutdownNow(); }
        });
        check("running transaction completes without interruption on timeout", () -> {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            AtomicBoolean finished = new AtomicBoolean();
            RequestTask<Integer> request = new RequestTask<>(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException error) { interrupted.set(true); }
                finished.set(true);
                return 1;
            });
            Thread worker = new Thread(request);
            worker.start();
            try {
                require(started.await(2, TimeUnit.SECONDS), "Request did not start");
                try { request.await(30, TimeUnit.MILLISECONDS); throw new AssertionError("Missing timeout"); }
                catch (TimeoutException expected) {}
                release.countDown();
                worker.join(2000);
                require(finished.get() && !interrupted.get(), "Transaction was interrupted or never completed");
            } finally { release.countDown(); worker.join(2000); }
        });
    }

    private static void restoration() throws Exception {
        check("recovery compares and restores only a changed requested target", () -> {
            RestorePlan plan = new RestorePlan(false);
            plan.add(8, "dialog-enhancer-enable", 1, 0, 0);
            require(plan.arguments.equals(Arrays.asList("restore", "8", "dialog-enhancer-enable", "0", "1")), plan.arguments.toString());
        });
        check("unchanged request never overwrites an unrelated later value", () -> {
            RestorePlan plan = new RestorePlan(false);
            plan.add(8, "dialog-enhancer-amount", 3, 3, 9);
            require(plan.isEmpty(), "Unchanged target entered recovery");
        });
        check("already restored target requires no write", () -> {
            RestorePlan plan = new RestorePlan(false);
            plan.add(8, "dialog-enhancer-enable", 1, 0, 1);
            require(plan.isEmpty(), "Restored target entered recovery");
        });
        check("completed write cannot overwrite a later writer", () ->
                expectFailure(() -> new RestorePlan(false).add(8, "dialog-enhancer-amount", 3, 0, 9), "参数已被其他操作改变"));
        check("lost receipt cannot overwrite an unrelated later writer", () ->
                expectFailure(() -> new RestorePlan(true).add(8, "dialog-enhancer-amount", 3, 0, 9), "参数已被其他操作改变"));
        check("lost receipt can recover every little-endian short-write prefix", () -> {
            int before = 0x01020304;
            int requested = 0x05060708;
            for (int bytes = 1; bytes < 4; bytes++) {
                int mask = -1 >>> (32 - bytes * 8);
                int current = (before & ~mask) | (requested & mask);
                RestorePlan plan = new RestorePlan(true);
                plan.add(8, "fixture", before, requested, current);
                require(!plan.isEmpty(), "Short write was not recoverable");
            }
        });
        check("known completed write never treats a later value as a short write", () ->
                expectFailure(() -> new RestorePlan(false).add(8, "fixture", 0x01020304, 0x05060708, 0x01020308), "参数已被其他操作改变"));
    }

    private static void locks() throws Exception {
        Path directory = Files.createTempDirectory("dcl-lock-test-");
        Path path = directory.resolve("operation.lock");
        try {
            check("rejected overlapping locks can be retried and released", () -> {
                try (ProfileLock first = new ProfileLock(path.toFile())) {
                    for (int index = 0; index < 1000; index++) {
                        expectFailure(() -> new ProfileLock(path.toFile()), "OverlappingFileLockException");
                    }
                }
                try (ProfileLock second = new ProfileLock(path.toFile())) {}
            });
            check("noncanonical lock paths are rejected before opening", () ->
                    expectFailure(() -> new ProfileLock(directory.resolve("unused/../operation.lock").toFile()), "Unsafe operation lock"));
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(directory); }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length >= 1) {
            if (arguments[0].equals("pid-hang")) {
                Files.writeString(Path.of(arguments[1]), String.valueOf(ProcessHandle.current().pid()));
                childMain("hang");
            } else childMain(arguments[0]);
            return;
        }
        processes();
        frames();
        requests();
        restoration();
        locks();
        System.out.println(passed + " backend runtime tests passed");
    }
}
