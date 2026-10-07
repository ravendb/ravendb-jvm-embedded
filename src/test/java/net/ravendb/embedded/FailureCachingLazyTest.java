package net.ravendb.embedded;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

public class FailureCachingLazyTest {

    @Test
    public void valueIsComputedOnce() {
        AtomicInteger calls = new AtomicInteger();
        FailureCachingLazy<String> lazy = new FailureCachingLazy<>(() -> "v" + calls.incrementAndGet());

        assertThat(lazy.isEvaluated()).isFalse();
        assertThat(lazy.hasValue()).isFalse();

        assertThat(lazy.getValue()).isEqualTo("v1");
        assertThat(lazy.getValue()).isEqualTo("v1");
        assertThat(calls).hasValue(1);

        assertThat(lazy.isEvaluated()).isTrue();
        assertThat(lazy.hasValue()).isTrue();
    }

    @Test
    public void runtimeExceptionIsCachedAndRethrown() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException boom = new IllegalStateException("boom");
        FailureCachingLazy<String> lazy = new FailureCachingLazy<>(() -> {
            calls.incrementAndGet();
            throw boom;
        });

        assertThat(catchThrowable(lazy::getValue)).isSameAs(boom);
        assertThat(catchThrowable(lazy::getValue)).isSameAs(boom);
        assertThat(calls).hasValue(1);

        // a failed evaluation still counts as evaluated - that is what lets restartServer() recover
        assertThat(lazy.isEvaluated()).isTrue();
        assertThat(lazy.hasValue()).isFalse();
    }

    // An Error must be cached too, or the next caller re-runs the factory and spawns a second server
    @Test
    public void errorIsCachedAndRethrown() {
        AtomicInteger calls = new AtomicInteger();
        Error boom = new NoClassDefFoundError("provider");
        FailureCachingLazy<String> lazy = new FailureCachingLazy<>(() -> {
            calls.incrementAndGet();
            throw boom;
        });

        assertThat(catchThrowable(lazy::getValue)).isSameAs(boom);
        assertThat(catchThrowable(lazy::getValue)).isSameAs(boom);
        assertThat(calls).hasValue(1);

        assertThat(lazy.isEvaluated()).isTrue();
        assertThat(lazy.hasValue()).isFalse();
    }

    @Test
    public void nullValueIsCached() {
        AtomicInteger calls = new AtomicInteger();
        FailureCachingLazy<String> lazy = new FailureCachingLazy<>(() -> {
            calls.incrementAndGet();
            return null;
        });

        assertThat(lazy.getValue()).isNull();
        assertThat(lazy.getValue()).isNull();
        assertThat(calls).hasValue(1);
        assertThat(lazy.hasValue()).isTrue();
    }
}
