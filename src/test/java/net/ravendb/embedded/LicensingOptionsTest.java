package net.ravendb.embedded;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class LicensingOptionsTest {

    @Test
    public void defaultsMatchCSharp() {
        LicensingOptions licensing = new LicensingOptions();

        assertThat(licensing.getLicense()).isNull();
        assertThat(licensing.getLicensePath()).isNull();
        assertThat(licensing.isEulaAccepted()).isFalse();
        assertThat(licensing.isDisableAutoUpdate()).isFalse();
        assertThat(licensing.isDisableAutoUpdateFromApi()).isFalse();
        assertThat(licensing.isDisableLicenseSupportCheck()).isTrue();
        assertThat(licensing.isThrowOnInvalidOrMissingLicense()).isFalse();
    }

    @Test
    public void serverOptionsExposesLicensingWithCSharpDefaults() {
        ServerOptions options = new ServerOptions();

        assertThat(options.getLicensing()).isNotNull();
        // EULA now defaults to false to match C# (was true in the old Java port).
        assertThat(options.getLicensing().isEulaAccepted()).isFalse();
        assertThat(options.isAcceptEula()).isFalse();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void deprecatedAcceptEulaDelegatesToLicensing() {
        ServerOptions options = new ServerOptions();

        options.setAcceptEula(true);
        assertThat(options.getLicensing().isEulaAccepted()).isTrue();
        assertThat(options.isAcceptEula()).isTrue();
    }
}
