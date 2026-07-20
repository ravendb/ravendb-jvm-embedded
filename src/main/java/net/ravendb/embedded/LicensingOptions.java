package net.ravendb.embedded;

/**
 * Licensing configuration passed to the embedded RavenDB server.
 * Ported from the C# {@code ServerOptions.LicensingOptions}. Defaults match C# exactly:
 * {@link #eulaAccepted} is {@code false} and {@link #disableLicenseSupportCheck} is {@code true}.
 */
@SuppressWarnings("unused")
public class LicensingOptions {

    private String license;
    private String licensePath;
    private boolean eulaAccepted = false;
    private boolean disableAutoUpdate = false;
    private boolean disableAutoUpdateFromApi = false;
    private boolean disableLicenseSupportCheck = true;
    private boolean throwOnInvalidOrMissingLicense = false;

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license;
    }

    public String getLicensePath() {
        return licensePath;
    }

    public void setLicensePath(String licensePath) {
        this.licensePath = licensePath;
    }

    public boolean isEulaAccepted() {
        return eulaAccepted;
    }

    public void setEulaAccepted(boolean eulaAccepted) {
        this.eulaAccepted = eulaAccepted;
    }

    public boolean isDisableAutoUpdate() {
        return disableAutoUpdate;
    }

    public void setDisableAutoUpdate(boolean disableAutoUpdate) {
        this.disableAutoUpdate = disableAutoUpdate;
    }

    public boolean isDisableAutoUpdateFromApi() {
        return disableAutoUpdateFromApi;
    }

    public void setDisableAutoUpdateFromApi(boolean disableAutoUpdateFromApi) {
        this.disableAutoUpdateFromApi = disableAutoUpdateFromApi;
    }

    public boolean isDisableLicenseSupportCheck() {
        return disableLicenseSupportCheck;
    }

    public void setDisableLicenseSupportCheck(boolean disableLicenseSupportCheck) {
        this.disableLicenseSupportCheck = disableLicenseSupportCheck;
    }

    public boolean isThrowOnInvalidOrMissingLicense() {
        return throwOnInvalidOrMissingLicense;
    }

    public void setThrowOnInvalidOrMissingLicense(boolean throwOnInvalidOrMissingLicense) {
        this.throwOnInvalidOrMissingLicense = throwOnInvalidOrMissingLicense;
    }
}
