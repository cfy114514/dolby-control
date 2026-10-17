package io.github.dcl;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/** Serializes edits and manual profile changes across module processes. */
final class ProfileLock implements AutoCloseable {
    private final RandomAccessFile file;
    private final FileChannel channel;
    private final FileLock lock;

    ProfileLock() throws Exception {
        this(new File("/data/adb/dolby_control_app/profile-operation.lock"));
    }

    ProfileLock(File path) throws Exception {
        if (!path.getCanonicalPath().equals(path.getAbsolutePath())) {
            throw new IOException("Unsafe operation lock");
        }
        RandomAccessFile opened = new RandomAccessFile(path, "rw");
        FileChannel openedChannel = opened.getChannel();
        FileLock acquired;
        try {
            acquired = openedChannel.tryLock();
            if (acquired == null) throw new IOException("Audio operation busy; retry shortly");
        } catch (Exception error) {
            // tryLock also throws for overlapping locks in this JVM; do not leak
            // the descriptor each time an operation has to retry.
            try { openedChannel.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            try { opened.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
        file = opened;
        channel = openedChannel;
        lock = acquired;
    }

    @Override public void close() throws IOException {
        try {
            lock.release();
        } finally {
            try { channel.close(); }
            finally { file.close(); }
        }
    }
}
