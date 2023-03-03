package net.ravendb.embedded;

import net.ravendb.client.exceptions.RavenException;
import net.ravendb.client.util.CertificateUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public class ServerOptions {

    private static final String BASE_DIRECTORY = Paths.get("").toAbsolutePath().toString();
    static String DEFAULT_SERVER_LOCATION = Paths.get(BASE_DIRECTORY, "RavenDBServer").toString();

    private String frameworkVersion = "7.0.2+";

    private String logsPath = Paths.get(BASE_DIRECTORY, "RavenDB", "Logs").toString();
    private String dataDirectory = Paths.get(BASE_DIRECTORY, "RavenDB").toString();

    IProvideRavenDBServer provider = new ExtractFromJarServerProvider();
    private String targetServerLocation = DEFAULT_SERVER_LOCATION;
    private String dotNetPath = "dotnet";
    private boolean clearTargetServerLocation = false;
    private boolean acceptEula = true;
    private String serverUrl;
    private Duration gracefulShutdownTimeout = Duration.ofSeconds(30);
    private Duration maxServerStartupTimeDuration = Duration.ofMinutes(1);
    private List<String> commandLineArgs = new ArrayList<>();

    static ServerOptions INSTANCE = new ServerOptions();

    private SecurityOptions security;

    public ServerOptions secured(String certificatePath) {
        return secured(certificatePath, "".toCharArray(), null);
    }

    public ServerOptions secured(String certificatePath, char[] certPassword) {
        return secured(certificatePath, certPassword, null);
    }

    @SuppressWarnings("UnusedReturnValue")
    public ServerOptions secured(String certificatePath, String caCertificatePath) {
        return secured(certificatePath, "".toCharArray(), caCertificatePath);
    }

    public ServerOptions secured(String certificatePath, char[] certPassword, String caCertificatePath) {
        if (certificatePath == null) {
            throw new IllegalArgumentException("certificate cannot be null");
        }

        if (this.security != null) {
            throw new IllegalStateException("The security has already been setup for this ServerOptions object");
        }

        try {
            KeyStore clientStore = KeyStore.getInstance("PKCS12");
            clientStore.load(new FileInputStream(certificatePath), certPassword);

            this.security = new SecurityOptions();
            this.security.setCertificatePath(certificatePath);
            this.security.setCertificatePassword(certPassword);
            this.security.setClientCertificate(clientStore);

            if (caCertificatePath != null) {
                this.security.setTrustStore(createTrustStore(caCertificatePath));
            }

            this.security.setServerCertificateThumbprint(CertificateUtils.extractThumbprintFromCertificate(clientStore));

        } catch (Exception e) {
            throw new RavenException("Unable to create secured server: " + e.getMessage(), e);
        }
        return this;
    }

    //TODO: renmove me?
    public ServerOptions secured(String certExec, String certExecArgs, String serverCertThumbprint,
                                 KeyStore clientCert, String caCertificatePath) {
        if (certExec == null) {
            throw new IllegalArgumentException("certExec cannot be null");
        }
        if (certExecArgs == null) {
            throw new IllegalArgumentException("certExecArgs cannot be null");
        }
        if (serverCertThumbprint == null) {
            throw new IllegalArgumentException("serverCertThumbprint cannot be null");
        }
        if (clientCert == null) {
            throw new IllegalArgumentException("clientCert cannot be null");
        }

        if (this.security != null) {
            throw new IllegalStateException("The security has already been setup for this ServerOptions object.");
        }

        try {

            this.security = new SecurityOptions();
            this.security.setClientCertificate(clientCert);
            this.security.setCertificateExec(certExec);
            this.security.setCertificateArguments(certExecArgs);
            this.security.setServerCertificateThumbprint(serverCertThumbprint);

            if (caCertificatePath != null) {
                this.security.setTrustStore(createTrustStore(caCertificatePath));
            }
        } catch (Exception e) {
            throw new RavenException("Unable to create secured server: " + e.getMessage(), e);
        }

        return this;
    }

    /**
     * Allows using external RavenDB server
     *
     * @param serverLocation Path to zip file or to extracted server directory
     */
    public void withExternalServer(String serverLocation) {
        this.provider = new ExternalServerProvider(serverLocation);
    }

    private static KeyStore createTrustStore(String caCertificatePath) throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);

        CertificateFactory x509 = CertificateFactory.getInstance("X509");

        try (InputStream source = new FileInputStream(new File(caCertificatePath))) {
            Certificate certificate = x509.generateCertificate(source);
            trustStore.setCertificateEntry("ca-cert", certificate);
        }

        return trustStore;
    }

    public String getLogsPath() {
        return logsPath;
    }

    public void setLogsPath(String logsPath) {
        this.logsPath = logsPath;
    }

    public String getDataDirectory() {
        return dataDirectory;
    }

    public void setDataDirectory(String dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public String getDotNetPath() {
        return dotNetPath;
    }

    public void setDotNetPath(String dotNetPath) {
        this.dotNetPath = dotNetPath;
    }

    public boolean isAcceptEula() {
        return acceptEula;
    }

    public void setAcceptEula(boolean acceptEula) {
        this.acceptEula = acceptEula;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public Duration getGracefulShutdownTimeout() {
        return gracefulShutdownTimeout;
    }

    public void setGracefulShutdownTimeout(Duration gracefulShutdownTimeout) {
        this.gracefulShutdownTimeout = gracefulShutdownTimeout;
    }

    public Duration getMaxServerStartupTimeDuration() {
        return maxServerStartupTimeDuration;
    }

    public void setMaxServerStartupTimeDuration(Duration maxServerStartupTimeDuration) {
        this.maxServerStartupTimeDuration = maxServerStartupTimeDuration;
    }

    public List<String> getCommandLineArgs() {
        return commandLineArgs;
    }

    public void setCommandLineArgs(List<String> commandLineArgs) {
        this.commandLineArgs = commandLineArgs;
    }

    public SecurityOptions getSecurity() {
        return security;
    }

    public String getFrameworkVersion() {
        return frameworkVersion;
    }

    public void setFrameworkVersion(String frameworkVersion) {
        this.frameworkVersion = frameworkVersion;
    }

    public String getTargetServerLocation() {
        return targetServerLocation;
    }

    public void setTargetServerLocation(String targetServerLocation) {
        this.targetServerLocation = targetServerLocation;
    }

    public boolean isClearTargetServerLocation() {
        return clearTargetServerLocation;
    }

    public void setClearTargetServerLocation(boolean clearTargetServerLocation) {
        this.clearTargetServerLocation = clearTargetServerLocation;
    }
}
