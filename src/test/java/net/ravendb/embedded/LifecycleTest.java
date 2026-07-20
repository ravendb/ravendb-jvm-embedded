package net.ravendb.embedded;

import net.ravendb.client.primitives.CleanCloseable;
import net.ravendb.client.primitives.Reference;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the server lifecycle APIs. Requires the RavenDB server payload under
 * {@code target/nuget/...} (run {@code mvn generate-resources}) and a JRE 9+ at runtime for
 * {@link EmbeddedServer#getServerProcessId()}. Disabled by default like the other server-spinning tests.
 */
@Disabled("requires the RavenDB server binary and a JRE 9+; run locally")
public class LifecycleTest {

    private static ServerOptions options(String tempDir) {
        ServerOptions serverOptions = new ServerOptions();
        serverOptions.setTargetServerLocation(Paths.get(tempDir, "RavenDBServer").toString());
        serverOptions.setDataDirectory(Paths.get(tempDir, "RavenDB").toString());
        serverOptions.setLogsPath(Paths.get(tempDir, "Logs").toString());
        serverOptions.provider = new CopyServerFromNugetProvider();
        return serverOptions;
    }

    @Test
    public void processIdRestartAndExitListener() throws Exception {
        Reference<String> tempDir = new Reference<>();
        try (CleanCloseable context = DirUtils.withTemporaryDir(tempDir)) {
            try (EmbeddedServer embedded = new EmbeddedServer()) {
                embedded.startServer(options(tempDir.value));

                long firstPid = embedded.getServerProcessId();
                assertThat(firstPid).isGreaterThan(0);

                // stopServer() must not dispose already-created document stores.
                embedded.getDocumentStore("Lifecycle");
                embedded.restartServer();

                long secondPid = embedded.getServerProcessId();
                assertThat(secondPid).isGreaterThan(0);
                assertThat(secondPid).isNotEqualTo(firstPid);

                // exit listener fires when the process dies.
                AtomicBoolean exited = new AtomicBoolean(false);
                CountDownLatch latch = new CountDownLatch(1);
                embedded.addServerProcessExitedListener(args -> {
                    exited.set(true);
                    latch.countDown();
                });

                embedded.stopServer();

                assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(exited.get()).isTrue();
            }
        }
    }
}
