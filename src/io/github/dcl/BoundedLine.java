package io.github.dcl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** One bounded, newline-terminated RPC frame with a total deadline, including slow senders. */
public final class BoundedLine {
    private BoundedLine() {}

    @FunctionalInterface
    public interface TimeoutSetter {
        void set(int milliseconds) throws IOException;
    }

    public static String read(InputStream input, int maximumBytes, int timeoutMillis,
                              TimeoutSetter timeout) throws IOException {
        if (maximumBytes < 1 || timeoutMillis < 1) {
            throw new IllegalArgumentException("Invalid frame limits");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[Math.min(maximumBytes, 4096)];
        for (;;) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Control request timed out");
            timeout.set((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int count = input.read(block);
            if (System.nanoTime() >= deadline) {
                throw new SocketTimeoutException("Control request timed out");
            }
            if (count < 0) throw new IOException("Incomplete control response");
            for (int index = 0; index < count; index++) {
                if (block[index] == '\n') return bytes.toString(StandardCharsets.UTF_8.name());
                if (bytes.size() >= maximumBytes) throw new IOException("Control frame too large");
                bytes.write(block[index]);
            }
        }
    }
}
