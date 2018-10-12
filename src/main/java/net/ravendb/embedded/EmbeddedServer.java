package net.ravendb.embedded;

import com.google.common.base.Stopwatch;
import net.ravendb.client.documents.DocumentStore;
import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.Lazy;
import net.ravendb.client.exceptions.ConcurrencyException;
import net.ravendb.client.exceptions.RavenException;
import net.ravendb.client.http.RequestExecutor;
import net.ravendb.client.primitives.CleanCloseable;
import net.ravendb.client.primitives.Reference;
import net.ravendb.client.primitives.Tuple;
import net.ravendb.client.serverwide.operations.CreateDatabaseOperation;
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
import java.util.function.BiFunction;

public class EmbeddedServer implements CleanCloseable {

    @SuppressWarnings("unused")
    public static EmbeddedServer INSTANCE = new EmbeddedServer();

    public static final String END_OF_STREAM_MARKER = "$$END_OF_STREAM$$";

    EmbeddedServer() {
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
        if (server == null) {
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
                    writer.println("q");
                    writer.println("y");
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
                System.out.println("About to clear: " + options.getTargetServerLocation());  //TODO: delete this line
                //TODO: FileUtils.deleteDirectory(new File(options.getTargetServerLocation()));
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

        Reference<String> urlRef = new Reference<>();
        Stopwatch startupDuration = Stopwatch.createStarted();

        String outputString = readOutput(process.getInputStream(), startupDuration, options, (line, builder) -> {

            if (line == null) {
                String errorString = readOutput(process.getErrorStream(), startupDuration, options, null);

                shutdownServerProcess(process);

                throw new IllegalStateException(buildStartupExceptionMessage(builder.toString(), errorString));
            }

            String prefix = "Server available on: ";
            if (line.startsWith(prefix)) {
                urlRef.value = line.substring(prefix.length());
                return true;
            }

            return false;
        });

        if (urlRef.value == null) {
            String errorString = readOutput(process.getErrorStream(), startupDuration, options, null);

            shutdownServerProcess(process);
            throw new IllegalStateException(buildStartupExceptionMessage(outputString, errorString));
        }

        return Tuple.create(urlRef.value, process);
    }

    private static String buildStartupExceptionMessage(String outputString, String errorString) {
        StringBuilder sb = new StringBuilder();
        sb.append("Unable to start the RavenDB Server");
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

    private static String readOutput(InputStream output, Stopwatch startupDuration, ServerOptions options,
                                     BiFunction<String, StringBuilder, Boolean> online) {

        BufferedReader reader = new BufferedReader(new InputStreamReader(output));

        BlockingQueue<String> readQueue = new ArrayBlockingQueue<>(50);

        CompletableFuture.runAsync(() -> {
            try {
                while (true) {
                    String line = reader.readLine();
                    if (line != null) {
                        readQueue.add(line);
                    } else {
                        readQueue.add(END_OF_STREAM_MARKER);
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        StringBuilder sb = new StringBuilder();

        try {
            while (true) {
                String line = readQueue.poll(5, TimeUnit.SECONDS);

                if (options.getMaxServerStartupTimeDuration().minus(startupDuration.elapsed()).isNegative()) {
                    return null;
                }

                if (line == null) {
                    continue;
                }

                if (END_OF_STREAM_MARKER.equals(line)) {
                    line = null;
                }

                if (line != null) {
                    sb.append(line);
                    sb.append(System.lineSeparator());
                }

                Reference<Boolean> shouldStop = new Reference<>(false);
                if (online != null) {
                    shouldStop.value = online.apply(line, sb);
                }

                if (shouldStop.value) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            throw new RavenException("Unable to read server output: " + e.getMessage(), e);
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
