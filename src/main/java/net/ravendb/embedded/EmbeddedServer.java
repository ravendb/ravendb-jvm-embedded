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
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.*;
import java.security.KeyStore;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class EmbeddedServer implements CleanCloseable {

    @SuppressWarnings("unused")
    public static EmbeddedServer INSTANCE = new EmbeddedServer();

    public EmbeddedServer() {
    }

    private static final Log logger = LogFactory.getLog(EmbeddedServer.class);

    private final AtomicReference<Lazy<Tuple<String, Process>>> _serverTask = new AtomicReference<>();

    private final ConcurrentMap<String, Lazy<IDocumentStore>> _documentStores = new ConcurrentHashMap<>();

    private KeyStore _certificate;
    private KeyStore _trustStore;
    private Duration _gracefulShutdownTimeout = Duration.ofSeconds(30);
    private Duration _processKillTimeout = Duration.ofSeconds(5);
    private ServerOptions _serverOptions;

    private final List<Consumer<ServerProcessExitedEventArgs>> _serverProcessExitedHandlers = new CopyOnWriteArrayList<>();

    @SuppressWarnings("unused")
    public void startServer() {
        startServer(null);
    }

    public void startServer(ServerOptions optionsParam) {
        ServerOptions options = ObjectUtils.firstNonNull(optionsParam, ServerOptions.INSTANCE);

        _serverOptions = options;
        _gracefulShutdownTimeout = options.getGracefulShutdownTimeout();
        _processKillTimeout = options.getProcessKillTimeout();

        if (options.getSecurity() != null) {
            _certificate = options.getSecurity().getClientCertificate();
            _trustStore = options.getSecurity().getTrustStore();
        }

        startServerInternal(options);
    }

    private void startServerInternal(ServerOptions options) {
        Lazy<Tuple<String, Process>> startServer = new Lazy<>(() -> runServer(options));

        if (!_serverTask.compareAndSet(null, startServer)) {
            throw new IllegalStateException("The server was already started");
        }

        startServer.getValue();
    }

    /**
     * Registers a listener invoked when the server process exits (for any reason, including
     * a crash or an explicit stop/restart). Mirrors the C# {@code ServerProcessExited} event.
     */
    @SuppressWarnings("unused")
    public void addServerProcessExitedListener(Consumer<ServerProcessExitedEventArgs> handler) {
        if (handler != null) {
            _serverProcessExitedHandlers.add(handler);
        }
    }

    @SuppressWarnings("unused")
    public void removeServerProcessExitedListener(Consumer<ServerProcessExitedEventArgs> handler) {
        _serverProcessExitedHandlers.remove(handler);
    }

    /**
     * Returns the OS process id of the running server. Requires Java 9+ at runtime
     * (uses {@code Process.pid()} reflectively so the library still compiles against Java 8).
     */
    @SuppressWarnings("unused")
    public long getServerProcessId() {
        Lazy<Tuple<String, Process>> server = _serverTask.get();
        if (server == null) {
            throw new IllegalStateException("Please run startServer() before trying to use the server.");
        }

        return getProcessId(server.getValue().second);
    }

    /**
     * Gracefully stops the server process without disposing the created document stores.
     * Mirrors the C# {@code StopServerAsync}.
     */
    @SuppressWarnings("unused")
    public void stopServer() {
        Lazy<Tuple<String, Process>> existing = _serverTask.get();
        if (_serverOptions == null || existing == null || !existing.isValueCreated()) {
            throw new IllegalStateException("Cannot call stopServer() before calling startServer().");
        }

        try {
            shutdownServerProcess(existing.getValue().second);
        } catch (Exception e) {
            // ignore - the process might already be dead, we failed to start, etc.
        }
    }

    /**
     * Stops the current server process and starts a fresh one with the original options.
     * Mirrors the C# {@code RestartServerAsync}.
     */
    @SuppressWarnings("unused")
    public void restartServer() {
        Lazy<Tuple<String, Process>> existing = _serverTask.get();
        if (_serverOptions == null || existing == null || !existing.isValueCreated()) {
            throw new IllegalStateException("Cannot call restartServer() before calling startServer().");
        }

        try {
            shutdownServerProcess(existing.getValue().second);
        } catch (Exception e) {
            // ignore - the process might already be dead, we failed to start, etc.
        }

        if (!_serverTask.compareAndSet(existing, null)) {
            throw new IllegalStateException("The server changed while restarting it. Are you calling restartServer() concurrently?");
        }

        startServerInternal(_serverOptions);
    }

    private static long getProcessId(Process process) {
        try {
            // Process.pid() was added in Java 9; call it reflectively to keep Java 8 source compatibility.
            Method pidMethod = Process.class.getMethod("pid");
            Object pid = pidMethod.invoke(process);
            return ((Number) pid).longValue();
        } catch (NoSuchMethodException e) {
            throw new RavenException("getServerProcessId() requires Java 9 or newer at runtime.", e);
        } catch (Exception e) {
            throw new RavenException("Unable to determine the server process id: " + e.getMessage(), e);
        }
    }

    private void notifyServerProcessExited() {
        if (_serverProcessExitedHandlers.isEmpty()) {
            return;
        }

        ServerProcessExitedEventArgs args = new ServerProcessExitedEventArgs();
        for (Consumer<ServerProcessExitedEventArgs> handler : _serverProcessExitedHandlers) {
            try {
                handler.accept(args);
            } catch (Exception e) {
                if (logger.isInfoEnabled()) {
                    logger.info("A ServerProcessExited listener threw an exception.", e);
                }
            }
        }
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

                Process killed = process.destroyForcibly();
                if (!killed.waitFor(_processKillTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    logger.warn("Server process did not terminate within " + _processKillTimeout + " after a forced kill.");
                }
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

        // Watch for the server process exiting (crash or explicit stop/restart) and notify listeners.
        Thread exitWatcher = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            notifyServerProcessExited();
        }, "RavenDB Embedded exit watcher");
        exitWatcher.setDaemon(true);
        exitWatcher.start();

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
        String base = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        String url = base + "studio/index.html?disableAnalytics=true";

        try {
            if (SystemUtils.IS_OS_WINDOWS) {
                new ProcessBuilder("cmd", "/c", "start", "RavenDB Studio", url).start();
            } else if (SystemUtils.IS_OS_MAC) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (IOException e) {
            throw new RavenException("Unable to open the Studio in a browser: " + e.getMessage(), e);
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
