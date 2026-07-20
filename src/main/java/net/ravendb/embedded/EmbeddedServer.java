package net.ravendb.embedded;

import net.ravendb.client.documents.DocumentStore;
import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.Lazy;
import net.ravendb.client.exceptions.ConcurrencyException;
import net.ravendb.client.exceptions.RavenException;
import net.ravendb.client.primitives.CleanCloseable;
import net.ravendb.client.primitives.Tuple;
import net.ravendb.client.serverwide.operations.CreateDatabaseOperation;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class EmbeddedServer implements CleanCloseable {

    @SuppressWarnings("unused")
    public static EmbeddedServer INSTANCE = new EmbeddedServer();

    public static final String END_OF_STREAM_MARKER = "$$END_OF_STREAM$$";

    public EmbeddedServer() {
    }

    private static final Log logger = LogFactory.getLog(EmbeddedServer.class);

    private final AtomicReference<Lazy<Tuple<String, Process>>> _serverTask = new AtomicReference<>();

    private final ConcurrentMap<String, Lazy<IDocumentStore>> _documentStores = new ConcurrentHashMap<>();

    private KeyStore _certificate;
    private KeyStore _trustStore;
    private Duration _gracefulShutdownTimeout;

    @SuppressWarnings("unused")
    public void startServer() {
        startServer(null);
    }

    public void startServer(ServerOptions optionsParam) {
        ServerOptions options = ObjectUtils.firstNonNull(optionsParam, ServerOptions.INSTANCE);

        _gracefulShutdownTimeout = options.getGracefulShutdownTimeout();

        Lazy<Tuple<String, Process>> startServer = new Lazy<>(() -> runServer(options));

        if (!_serverTask.compareAndSet(null, startServer)) {
            throw new IllegalStateException("The server was already started");
        }

        if (options.getSecurity() != null) {
            _certificate = options.getSecurity().getClientCertificate();
            _trustStore = options.getSecurity().getTrustStore();
        }

        startServer.getValue();
    }

    public IDocumentStore getDocumentStore(String database) {
        return getDocumentStore(new DatabaseOptions(database));
    }

    public IDocumentStore getDocumentStore(DatabaseOptions options) {
        String databaseName = options.getDatabaseRecord().getDatabaseName();

        if (StringUtils.isBlank(databaseName)) {
            throw new IllegalArgumentException("DatabaseName cannot be null or whitespace");
        }

        if (logger.isInfoEnabled()) {
            logger.info("Creating document store for '" + databaseName + "'.");
        }

        Lazy<IDocumentStore> lazy = new Lazy<>(() -> {
            String serverUrl = getServerUri();

            DocumentStore store = new DocumentStore(serverUrl, databaseName);
            store.setCertificate(_certificate);
            store.setTrustStore(_trustStore);
            store.setConventions(options.getConventions());

            store.addAfterCloseListener((sender, event) -> _documentStores.remove(databaseName));

            store.initialize();

            if (!options.isSkipCreatingDatabase()) {
                tryCreateDatabase(options, store);
            }

            return store;
        });

        return this._documentStores.computeIfAbsent(databaseName, dbName -> lazy).getValue();
    }

    private void tryCreateDatabase(DatabaseOptions options, IDocumentStore store) {
        try {
            store.maintenance().server().send(new CreateDatabaseOperation(options.getDatabaseRecord()));
        } catch (ConcurrencyException e) {
            // Expected behaviour when the database is already exists
            if (logger.isInfoEnabled()) {
                logger.info(options.getDatabaseRecord().getDatabaseName() + " already exists.");
            }
        }
    }

    public String getServerUri() {
        AtomicReference<Lazy<Tuple<String, Process>>> server = _serverTask;
        if (server.get() == null) {
            throw new IllegalStateException("Please run startServer() before trying to use the server.");
        }

        return server.get().getValue().first;
    }

    private void shutdownServerProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }

        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (process) {
            if (!process.isAlive()) {
                return;
            }

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Try shutdown server gracefully.");
                }

                try (OutputStream stream = process.getOutputStream();
                PrintWriter writer = new PrintWriter(stream)) {
                    writer.println("shutdown no-confirmation");
                }

                if (process.waitFor(_gracefulShutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (Exception e) {
                if (logger.isInfoEnabled()) {
                    logger.info("Failed to gracefully shutdown server in " + _gracefulShutdownTimeout.toString(), e);
                }
            }

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Killing global server");
                }

                process.destroyForcibly().waitFor();
            } catch (Exception e) {
                if (logger.isInfoEnabled()) {
                    logger.info("Failed to kill server process.");
                }
            }
        }
    }

    private Tuple<String, Process> runServer(ServerOptions options) {
        try {
            if (options.isClearTargetServerLocation()) {
                FileUtils.deleteDirectory(new File(options.getTargetServerLocation()));
            }

            options.provider.provide(options.getTargetServerLocation());
        } catch (IOException e) {
            logger.error("Failed to spawn server files. " + e.getMessage(), e);
            throw new IllegalStateException("Failed to spawn server files. " + e.getMessage(), e);
        }

        Process process = RavenServerRunner.run(options);

        if (logger.isInfoEnabled()) {
            logger.info("Starting global server");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownServerProcess(process)));

        Duration timeout = options.getMaxServerStartupTimeDuration();

        String url;
        try {
            url = awaitServerUrl(process.getInputStream(), process.getErrorStream(), timeout);
        } catch (RuntimeException e) {
            shutdownServerProcess(process);
            throw e;
        }

        return Tuple.create(url, process);
    }

    /**
     * Reads the server's stdout/stderr on dedicated threads (which keep draining for the process lifetime),
     * and returns the announced server URL. Throws {@link ServerStartupTimeoutException} if the URL does not
     * appear within {@code timeout}, or {@link IllegalStateException} if the server dies during startup.
     * The caller is responsible for terminating the process on failure.
     */
    static String awaitServerUrl(InputStream stdoutStream, InputStream stderrStream, Duration timeout) {
        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();
        Object stdoutLock = new Object();
        Object stderrLock = new Object();

        CompletableFuture<String> urlFuture = new CompletableFuture<>();
        CompletableFuture<Void> stderrEndFuture = new CompletableFuture<>();

        // Drain stdout for the whole process lifetime; complete urlFuture when the server announces its URL.
        Thread stdoutReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdoutStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stdoutLock) {
                        stdoutBuilder.append(line).append(System.lineSeparator());
                    }

                    String prefix = "Server available on: ";
                    if (line.startsWith(prefix)) {
                        urlFuture.complete(line.substring(prefix.length()));
                    }
                }
            } catch (IOException e) {
                // stream closed - the process has exited
            }
        }, "RavenDB Embedded stdout reader");
        stdoutReader.setDaemon(true);

        // Drain stderr for the whole process lifetime; complete stderrEndFuture when the stream ends (process exit).
        Thread stderrReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderrStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrLock) {
                        stderrBuilder.append(line).append(System.lineSeparator());
                    }
                }
            } catch (IOException e) {
                // stream closed - the process has exited
            } finally {
                stderrEndFuture.complete(null);
            }
        }, "RavenDB Embedded stderr reader");
        stderrReader.setDaemon(true);

        stdoutReader.start();
        stderrReader.start();

        try {
            // Race: server ready (stdout URL) vs. server died (stderr stream ended) vs. startup timeout.
            CompletableFuture.anyOf(urlFuture, stderrEndFuture).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            String stdout = snapshot(stdoutBuilder, stdoutLock);
            String stderr = snapshot(stderrBuilder, stderrLock);
            throw new ServerStartupTimeoutException(buildStartupExceptionMessage(stdout, stderr, true, timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RavenException("Interrupted while waiting for the RavenDB Server to start", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(buildStartupExceptionMessage(
                    snapshot(stdoutBuilder, stdoutLock), snapshot(stderrBuilder, stderrLock), false, timeout), e);
        }

        if (urlFuture.isDone()) {
            return urlFuture.getNow(null);
        }

        // stderr stream ended before the URL appeared: allow a short grace for trailing error lines,
        // but if the URL shows up during that window, treat startup as successful.
        try {
            return urlFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // still no URL - fall through to failure
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String stdout = snapshot(stdoutBuilder, stdoutLock);
        String stderr = snapshot(stderrBuilder, stderrLock);
        throw new IllegalStateException(buildStartupExceptionMessage(stdout, stderr, false, timeout));
    }

    private static String snapshot(StringBuilder builder, Object lock) {
        synchronized (lock) {
            return builder.toString();
        }
    }

    private static String buildStartupExceptionMessage(String outputString, String errorString, boolean isTimeout, Duration timeout) {
        StringBuilder sb = new StringBuilder();
        if (isTimeout) {
            sb.append("Server failed to start in ").append(timeout.getSeconds()).append(" s.");
        } else {
            sb.append("Unable to start the RavenDB Server");
        }
        sb.append(System.lineSeparator());

        if (StringUtils.isNotBlank(errorString)) {
            sb.append("Error:");
            sb.append(System.lineSeparator());
            sb.append(errorString);
            sb.append(System.lineSeparator());
        }

        if (StringUtils.isNotBlank(outputString)) {
            sb.append("Output:");
            sb.append(System.lineSeparator());
            sb.append(outputString);
            sb.append(System.lineSeparator());
        }

        return sb.toString();
    }

    @SuppressWarnings("unused")
    public void openStudioInBrowser() {
        String serverUrl = getServerUri();

        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            try {
                desktop.browse(new URI(serverUrl));
            } catch (IOException | URISyntaxException e) {
                throw new RuntimeException(e);
            }
        } else {
            Runtime runtime = Runtime.getRuntime();
            try {
                runtime.exec("xdg-open " + serverUrl);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void close() {
        Lazy<Tuple<String, Process>> lazy = _serverTask.getAndSet(null);
        if (lazy == null || !lazy.isValueCreated()) {
            return;
        }

        Process process = lazy.getValue().second;
        shutdownServerProcess(process);

        for (Map.Entry<String, Lazy<IDocumentStore>> item : _documentStores.entrySet()) {
            if (item.getValue().isValueCreated()) {
                item.getValue().getValue().close();
            }
        }

        _documentStores.clear();
    }
}
