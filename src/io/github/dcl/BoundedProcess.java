package io.github.dcl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a fixed helper command without letting a hung child block the service forever. */
public final class BoundedProcess {
    private BoundedProcess() {}

    /** Preserve a failed helper's structured receipt instead of losing it in a message. */
    public static final class Failure extends IOException {
        public final int exitCode;
        public final String output;

        Failure(int exitCode, String output) {
            super("Command failed (" + exitCode + "): " + output);
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /** A caller must not begin recovery while the previous writer could still run. */
    public static final class TerminationFailure extends IOException {
        TerminationFailure() { super("Audio helper could not be stopped; recovery deferred"); }
    }

    private static void stop(Process process) throws TerminationFailure {
        if (!process.isAlive()) return;
        process.destroyForcibly();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        boolean interrupted = false;
        try {
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TerminationFailure();
                try {
                    process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException error) {
                    // Finish confirming termination before exposing any failure
                    // to a caller that may begin recovery, then restore the flag.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public static String run(List<String> arguments, String identity, long timeoutMillis,
                             int maximumOutputBytes) throws Exception {
        if (timeoutMillis <= 0 || maximumOutputBytes < 1) {
            throw new IllegalArgumentException("Invalid command limits");
        }
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        if (identity != null) builder.environment().put("DCL_EXPECT_PROCESS", identity);
        Process process = builder.start();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        FutureTask<byte[]> output = new FutureTask<>(() -> {
            try (InputStream input = process.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] block = new byte[4096];
                for (int count; (count = input.read(block)) != -1;) {
                    if (count > maximumOutputBytes - bytes.size()) {
                        process.destroyForcibly();
                        throw new IOException("Oversize command output");
                    }
                    bytes.write(block, 0, count);
                }
                return bytes.toByteArray();
            }
        });
        Thread reader = new Thread(output, "dcl-helper-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Audio helper timed out");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Audio helper timed out");
            String text = new String(output.get(remaining, TimeUnit.NANOSECONDS),
                    StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new Failure(process.exitValue(), text);
            }
            return text;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Cannot read command output", cause);
        } catch (TimeoutException error) {
            throw new IOException("Audio helper timed out", error);
        } finally {
            try {
                // destroyForcibly() alone does not wait for actual termination.
                stop(process);
            } finally {
                output.cancel(true);
                if (!process.isAlive()) {
                    try { process.getInputStream().close(); } catch (IOException ignored) {}
                    try { process.getOutputStream().close(); } catch (IOException ignored) {}
                    try { process.getErrorStream().close(); } catch (IOException ignored) {}
                }
            }
        }
    }
}
