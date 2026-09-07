package net.ravendb.embedded;

import java.util.function.Supplier;

/**
 * A lazy value whose factory runs at most once: a failure is cached and rethrown on every later call.
 * <p>
 * {@link net.ravendb.client.documents.Lazy} retries after a failure, which for the server task means
 * a failed {@code startServer()} would silently relaunch the whole server - re-provisioning the
 * binaries and spawning another process - from an innocent getter such as {@code getServerUri()}.
 * C# relies on {@code Lazy<Task<T>>} caching the faulted task; this is the same contract.
 */
final class FailureCachingLazy<T> {

    private final Supplier<T> valueFactory;

    private volatile boolean evaluated;
    private T value;
    private RuntimeException failure;

    FailureCachingLazy(Supplier<T> valueFactory) {
        this.valueFactory = valueFactory;
    }

    /**
     * Whether the factory has already run, with either a value or a failure. The counterpart of
     * {@code Lazy<Task<T>>.IsValueCreated} in C#, which is true for a faulted start as well - that is
     * what lets {@code restartServer()} recover from one.
     */
    boolean isEvaluated() {
        return evaluated;
    }

    /**
     * Whether the factory has run and produced a value.
     */
    boolean hasValue() {
        return evaluated && failure == null;
    }

    T getValue() {
        if (!evaluated) {
            synchronized (this) {
                if (!evaluated) {
                    try {
                        value = valueFactory.get();
                    } catch (RuntimeException e) {
                        failure = e;
                    }
                    evaluated = true;
                }
            }
        }

        if (failure != null) {
            throw failure;
        }

        return value;
    }
}
