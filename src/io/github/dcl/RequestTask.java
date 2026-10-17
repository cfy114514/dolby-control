package io.github.dcl;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Expired queued requests must not mutate audio later; running transactions finish safely. */
final class RequestTask<T> extends FutureTask<T> {
    RequestTask(Callable<T> operation) { super(operation); }

    RequestTask(Callable<T> operation, long deadline, LongSupplier clock) {
        super(() -> {
            if (clock.getAsLong() >= deadline) throw new TimeoutException("音效操作已过期，请重试");
            return operation.call();
        });
    }

    T await(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return get(timeout, unit);
        } catch (InterruptedException | TimeoutException error) {
            // false prevents interruption in the middle of a native transaction
            // or its rollback, while FutureTask skips work that has not started.
            cancel(false);
            throw error;
        }
    }
}
