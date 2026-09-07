package net.ravendb.embedded;

import net.ravendb.client.primitives.CleanCloseable;
import net.ravendb.client.primitives.Reference;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for the server lifecycle APIs. Requires the RavenDB server payload under
 * {@code target/nuget/...} (produced by {@code mvn generate-resources}) and a JRE 9+ at runtime for
 * {@link EmbeddedServer#getServerProcessId()}. Skipped - not silently disabled - when either is missing.
 */
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
        assumeTrue(new File(CopyServerFromNugetProvider.SERVER_FILES).isDirectory(),
                "RavenDB server payload missing - run `mvn generate-resources`");
        assumeTrue(!System.getProperty("java.specification.version").startsWith("1."),
                "getServerProcessId() needs Process.pid() (JRE 9+)");

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

    /**
     * A server that dies while booting must notify exit listeners as well - the watcher is started
     * before the startup handshake, the way C# subscribes to {@code Process.Exited} before waiting
     * for the URL. The boot is forced to fail by pointing the server at a port this test holds.
     */
    @Test
    public void exitListenerFiresWhenServerDiesDuringStartup() throws Exception {
        assumeTrue(new File(CopyServerFromNugetProvider.SERVER_FILES).isDirectory(),
                "RavenDB server payload missing - run `mvn generate-resources`");

        Reference<String> tempDir = new Reference<>();
        try (CleanCloseable context = DirUtils.withTemporaryDir(tempDir);
             ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {

            ServerOptions serverOptions = options(tempDir.value);
            serverOptions.setServerUrl("http://127.0.0.1:" + occupied.getLocalPort());
            serverOptions.setMaxServerStartupTimeDuration(Duration.ofSeconds(30));

            CountDownLatch exited = new CountDownLatch(1);

            try (EmbeddedServer embedded = new EmbeddedServer()) {
                embedded.addServerProcessExitedListener(args -> exited.countDown());

                assertThatThrownBy(() -> embedded.startServer(serverOptions))
                        .isInstanceOf(RuntimeException.class);

                assertThat(exited.await(30, TimeUnit.SECONDS))
                        .as("a boot failure must reach the exit listeners")
                        .isTrue();
            }
        }
    }
}
