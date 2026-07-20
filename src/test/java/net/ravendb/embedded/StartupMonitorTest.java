package net.ravendb.embedded;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class StartupMonitorTest {

    @Test
    public void returnsUrlWhenServerAnnouncesIt() {
        InputStream stdout = new ByteArrayInputStream(
                ("Booting up...\r\n" +
                 "Server available on: http://127.0.0.1:8080\r\n").getBytes(StandardCharsets.UTF_8));
        InputStream stderr = new ByteArrayInputStream(new byte[0]);

        String url = EmbeddedServer.awaitServerUrl(stdout, stderr, Duration.ofSeconds(5));

        assertThat(url).isEqualTo("http://127.0.0.1:8080");
    }

    @Test
    public void timeoutCapturesStderr() throws IOException {
        // stdout stays open but never announces a URL -> forces the timeout path.
        PipedOutputStream stdoutOut = new PipedOutputStream();
        PipedInputStream stdout = new PipedInputStream(stdoutOut);

        // stderr has an error line and stays open, so the stream does NOT end (not a fail-fast, a real timeout).
        PipedOutputStream stderrOut = new PipedOutputStream();
        PipedInputStream stderr = new PipedInputStream(stderrOut);
        stderrOut.write("Fatal boot error: port already in use\r\n".getBytes(StandardCharsets.UTF_8));
        stderrOut.flush();

        try {
            assertThatThrownBy(() -> EmbeddedServer.awaitServerUrl(stdout, stderr, Duration.ofMillis(700)))
                    .isInstanceOf(ServerStartupTimeoutException.class)
                    .hasMessageContaining("Server failed to start in")
                    // The pre-fix code lost stderr on timeout; assert it is now included.
                    .hasMessageContaining("Fatal boot error: port already in use");
        } finally {
            stdoutOut.close();
            stderrOut.close();
        }
    }

    @Test
    public void failFastWhenStreamsEndWithoutUrl() {
        InputStream stdout = new ByteArrayInputStream(
                "Starting, but about to crash\r\n".getBytes(StandardCharsets.UTF_8));
        InputStream stderr = new ByteArrayInputStream(
                "Unhandled exception: boom\r\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> EmbeddedServer.awaitServerUrl(stdout, stderr, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to start the RavenDB Server")
                .hasMessageContaining("Unhandled exception: boom");
    }
}
