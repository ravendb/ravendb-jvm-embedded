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
import java.lang.reflect.Field;
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

    private final AtomicReference<FailureCachingLazy<Tuple<String, Process>>> _serverTask = new AtomicReference<>();

    private final AtomicReference<Thread> _shutdownHook = new AtomicReference<>();

    private final ConcurrentMap<String, Lazy<IDocumentStore>> _documentStores = new ConcurrentHashMap<>();

    private KeyStore _certificate;
    private KeyStore _trustStore;
    private ServerOptions _serverOptions;

    private final List<Consumer<ServerProcessExitedEventArgs>> _serverProcessExitedHandlers = new CopyOnWriteArrayList<>();

    @SuppressWarnings("unused")
    public void startServer() {
        startServer(null);
    }

    public void startServer(ServerOptions optionsParam) {
        ServerOptions options = ObjectUtils.firstNonNull(optionsParam, ServerOptions.INSTANCE);

        _serverOptions = options;

        if (options.getSecurity() != null) {
            _certificate = options.getSecurity().getClientCertificate();
            _trustStore = options.getSecurity().getTrustStore();
        }

        startServerInternal(options);
    }

    private void startServerInternal(ServerOptions options) {
        FailureCachingLazy<Tuple<String, Process>> startServer = new FailureCachingLazy<>(() -> runServer(options));

        if (!_serverTask.compareAndSet(null, startServer)) {
            throw new IllegalStateException("The server was already started");
        }

        startServer.getValue();
    }

    /**
     * Registers a listener invoked when the server process exits (for any reason, including
     * a crash or an explicit stop/restart).
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
     * Returns the OS process id of the running server.
     */
    @SuppressWarnings("unused")
    public long getServerProcessId() {
        FailureCachingLazy<Tuple<String, Process>> server = _serverTask.get();
        if (server == null) {
            throw new IllegalStateException("Please run startServer() before trying to use the server.");
        }

        return getProcessId(server.getValue().second);
    }

    /**
     * Gracefully stops the server process without disposing the created document stores.
     */
    @SuppressWarnings("unused")
    public void stopServer() {
        FailureCachingLazy<Tuple<String, Process>> existing = _serverTask.get();
        if (_serverOptions == null || existing == null || !existing.isEvaluated()) {
            throw new IllegalStateException("Cannot call stopServer() before calling startServer().");
        }

        Process process = existing.getValue().second;

        try {
            shutdownServerProcess(process);
        } catch (Exception e) {
            // ignore - the process might already be dead, we failed to start, etc.
        }
    }

    /**
     * Stops the current server process and starts a fresh one with the original options.
     */
    @SuppressWarnings("unused")
    public void restartServer() {
        FailureCachingLazy<Tuple<String, Process>> existing = _serverTask.get();
        if (_serverOptions == null || existing == null || !existing.isEvaluated()) {
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
        //Java 9+
        try {
            Method pidMethod = Process.class.getMethod("pid");
            return ((Number) pidMethod.invoke(process)).longValue();
        } catch (NoSuchMethodException runningOnJava8) {
            // fall through to the Java 8 fallback below
        } catch (Exception e) {
            throw new RavenException("Unable to determine the server process id: " + e.getMessage(), e);
        }

        // Fallback for Java 8+ compatibility.
        try {
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            return ((Number) pidField.get(process)).longValue();
        } catch (NoSuchFieldException windowsProcessImpl) {
            // Windows ProcessImpl only has a native 'handle', not a pid.
            throw new RavenException(
                    "getServerProcessId() on Java 8 is only supported on Unix-like systems. " +
                            "Run on Java 9+ for cross-platform support.", windowsProcessImpl);
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
        FailureCachingLazy<Tuple<String, Process>> server = _serverTask.get();
        if (server == null) {
            throw new IllegalStateException("Please run startServer() before trying to use the server.");
        }

        return server.getValue().first;
    }

    private void shutdownServerProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }

        Duration gracefulShutdownTimeout = _serverOptions.getGracefulShutdownTimeout();
        Duration processKillTimeout = _serverOptions.getProcessKillTimeout();

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

                if (process.waitFor(gracefulShutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (Exception e) {
                if (logger.isInfoEnabled()) {
                    logger.info("Failed to gracefully shutdown server in " + gracefulShutdownTimeout.toString(), e);
                }
            }

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Killing global server");
                }

                Process killed = process.destroyForcibly();
                if (!killed.waitFor(processKillTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    logger.warn("Server process did not terminate within " + processKillTimeout + " after a forced kill.");
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

        registerShutdownHook(process);
        watchForProcessExit(process);

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
     * Keeps a single hook per instance: a restart replaces the previous process' hook instead of
     * leaving one behind per start.
     */
    private void registerShutdownHook(Process process) {
        Thread hook = new Thread(() -> shutdownServerProcess(process), "RavenDB Embedded shutdown hook");

        Thread previous = _shutdownHook.getAndSet(hook);
        Runtime.getRuntime().addShutdownHook(hook);

        removeShutdownHook(previous);
    }

    private void removeShutdownHook(Thread hook) {
        if (hook == null) {
            return;
        }

        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shutdownInProgress) {
            // the JVM is already going down - the hook is about to run and will find a dead process
        }
    }

    /**
     * Started before the startup handshake, so a server that dies while booting still notifies
     * listeners - C# subscribes to {@code Process.Exited} before waiting for the URL too.
     */
    private void watchForProcessExit(Process process) {
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
    }

    static String awaitServerUrl(InputStream stdoutStream, InputStream stderrStream, Duration timeout) {
        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();
        Object stdoutLock = new Object();
        Object stderrLock = new Object();

        CompletableFuture<String> urlFuture = new CompletableFuture<>();
        CompletableFuture<Void> stderrEndFuture = new CompletableFuture<>();

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
        FailureCachingLazy<Tuple<String, Process>> lazy = _serverTask.getAndSet(null);
        if (lazy == null || !lazy.isEvaluated()) {
            return;
        }

        if (lazy.hasValue()) {
            shutdownServerProcess(lazy.getValue().second);
        }

        removeShutdownHook(_shutdownHook.getAndSet(null));

        for (Map.Entry<String, Lazy<IDocumentStore>> item : _documentStores.entrySet()) {
            if (item.getValue().isValueCreated()) {
                item.getValue().getValue().close();
            }
        }

        _documentStores.clear();
    }
}
