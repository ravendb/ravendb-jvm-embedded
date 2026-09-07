package net.ravendb.embedded;

import net.ravendb.client.exceptions.RavenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A failed start must behave like the C# {@code Lazy<Task>}: the failure is cached and rethrown, so
 * an innocent getter cannot silently relaunch the whole server behind the caller's back. Needs no
 * RavenDB payload - the start fails while building the command line, before a process is spawned.
 */
public class StartFailureTest {

    @Test
    public void failedStartIsCachedAndOnlyRestartRetries(@TempDir Path dir) {
        AtomicInteger provideCount = new AtomicInteger();

        ServerOptions options = new ServerOptions();
        options.setTargetServerLocation(dir.resolve("no-server-here").toString()); // no Raven.Server.dll
        options.setDataDirectory(dir.resolve("data").toString());
        options.setLogsPath(dir.resolve("logs").toString());
        options.setFrameworkVersion(null);
        options.provider = targetDirectory -> provideCount.incrementAndGet();

        EmbeddedServer embedded = new EmbeddedServer();

        Throwable failure = catchThrowable(() -> embedded.startServer(options));
        assertThat(failure)
                .isInstanceOf(RavenException.class)
                .hasMessageContaining("Server file was not found");
        assertThat(provideCount).hasValue(1);

        // the very same failure comes back, and nothing is provisioned or spawned a second time
        assertThat(catchThrowable(embedded::getServerUri)).isSameAs(failure);
        assertThat(catchThrowable(() -> embedded.getDocumentStore("Whatever"))).isSameAs(failure);
        assertThat(catchThrowable(embedded::stopServer)).isSameAs(failure);
        assertThat(provideCount).hasValue(1);

        // ...and startServer() still refuses a second start, as in C#
        assertThat(catchThrowable(() -> embedded.startServer(options)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");

        // restartServer() is the way out, exactly like C# RestartServerAsync()
        assertThat(catchThrowable(embedded::restartServer)).isInstanceOf(RavenException.class);
        assertThat(provideCount).hasValue(2);

        // close() after a failed start must not rethrow the startup failure
        assertThatCode(embedded::close).doesNotThrowAnyException();

        // and a closed instance can be started again
        assertThat(catchThrowable(() -> embedded.startServer(options))).isInstanceOf(RavenException.class);
        assertThat(provideCount).hasValue(3);
    }
}
