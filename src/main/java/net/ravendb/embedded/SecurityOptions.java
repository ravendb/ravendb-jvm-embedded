package net.ravendb.embedded;

import java.security.KeyStore;

@SuppressWarnings("unused")
public class SecurityOptions {

    private String certificatePath;
    private char[] certificatePassword;
    private KeyStore clientCertificate;
    private KeyStore trustStore;
    private String certificateExec;
    private String certificateArguments;
    private String serverCertificateThumbprint;

    SecurityOptions() {
    }

    public KeyStore getTrustStore() {
        return trustStore;
    }

    public KeyStore getClientCertificate() {
        return clientCertificate;
    }

    public String getCertificatePath() {
        return certificatePath;
    }

    public char[] getCertificatePassword() {
        return certificatePassword;
    }

    public String getCertificateExec() {
        return certificateExec;
    }

    public String getCertificateArguments() {
        return certificateArguments;
    }

    public String getServerCertificateThumbprint() {
        return serverCertificateThumbprint;
    }

    void setCertificatePath(String certificatePath) {
        this.certificatePath = certificatePath;
    }

    void setCertificatePassword(char[] certificatePassword) {
        this.certificatePassword = certificatePassword;
    }

    void setCertificateExec(String certificateExec) {
        this.certificateExec = certificateExec;
    }

    void setCertificateArguments(String certificateArguments) {
        this.certificateArguments = certificateArguments;
    }

    void setServerCertificateThumbprint(String serverCertificateThumbprint) {
        this.serverCertificateThumbprint = serverCertificateThumbprint;
    }

    void setClientCertificate(KeyStore clientCertificate) {
        this.clientCertificate = clientCertificate;
    }

    void setTrustStore(KeyStore trustStore) {
        this.trustStore = trustStore;
    }
}
