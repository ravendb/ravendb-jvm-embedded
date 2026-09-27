package net.ravendb.embedded;

import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class RavenServerRunnerTest {

    private static final String ALLOW_AMBIGUOUS_COMMANDS = "jdk.lang.Process.allowAmbiguousCommands";

    private static final String SPEC_VERSION = "java.specification.version";

    private static final String LICENSE_JSON =
            "{\"Id\":\"a1b2\",\"Name\":\"Bogus Corp Ltd\",\"Keys\":[\"AAAABBBBCCCC\"]}";

    private static final String TRAILING_BACKSLASH_ARG = "--Security.Certificate.Exec.Path=C:\\raven certs\\\\";

    private static ServerOptions optionsFor(Path serverDir) {
        ServerOptions options = new ServerOptions();
        options.setTargetServerLocation(serverDir.toString());
        options.setDataDirectory(serverDir.resolve("data").toString());
        options.setLogsPath(serverDir.resolve("logs").toString());
        options.setFrameworkVersion(null); // avoid invoking the `dotnet --info` probe in unit tests
        return options;
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private static int indexOfArgStartingWith(List<String> args, String prefix) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** Replaces a dynamic argument value, checked against {@code valuePattern}, with a placeholder. */
    private static void normalize(List<String> args, String prefix, String valuePattern, String placeholder) {
        int index = indexOfArgStartingWith(args, prefix);
        assertThat(index).as("%s must be present", prefix).isGreaterThanOrEqualTo(0);
        assertThat(args.get(index).substring(prefix.length()))
                .as("value of %s", prefix)
                .matches(valuePattern);
        args.set(index, prefix + placeholder);
    }

    /** A throwaway PKCS12 holding a single self-signed entry - enough for the thumbprint lookup. */
    private static Path generateClientCertificate(Path dir, char[] password) throws Exception {
        Path store = dir.resolve("client.pfx");
        String keytool = SystemUtils.IS_OS_WINDOWS ? "keytool.exe" : "keytool";

        List<String> command = Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", keytool).toString(),
                "-genkeypair", "-alias", "raven-embedded-test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=raven-embedded-test",
                "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", new String(password), "-keypass", new String(password));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        assertThat(process.waitFor()).as("keytool failed: %s", output).isZero();
        return store;
    }

    /** The same certificate re-saved under an empty password - what a passwordless PKCS12 looks like. */
    private static Path generatePasswordlessClientCertificate(Path dir) throws Exception {
        char[] password = "changeit".toCharArray();
        KeyStore store = load(generateClientCertificate(dir, password), password);

        Path passwordless = dir.resolve("client-nopass.pfx");
        try (OutputStream out = Files.newOutputStream(passwordless)) {
            store.store(out, new char[0]);
        }
        return passwordless;
    }

    private static KeyStore load(Path pkcs12, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(pkcs12)) {
            store.load(in, password);
        }
        return store;
    }

    @Test
    public void fullyPopulatedOptionsProduceTheExactArgumentList(@TempDir Path serverDir) throws IOException {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");
        options.setServerUrl("http://127.0.0.1:9999");
        options.setFrameworkVersion("8.0.10"); // an exact version is matched verbatim, without a `dotnet --info` probe
        options.getCommandLineArgs().add("--Logs.Mode=Information");

        LicensingOptions licensing = options.getLicensing();
        licensing.setLicensePath("/etc/ravendb/license.json");
        licensing.setEulaAccepted(true);
        licensing.setDisableAutoUpdate(true);
        licensing.setDisableAutoUpdateFromApi(true);
        licensing.setDisableLicenseSupportCheck(false);
        licensing.setThrowOnInvalidOrMissingLicense(true);

        List<String> cmd = new ArrayList<>(RavenServerRunner.buildCommandLine(options));
        normalize(cmd, "--Embedded.ParentProcessId=", "\\d+", "<pid>");

        assertThat(cmd).containsExactly(
                "dotnet",
                "--fx-version",
                "8.0.10",
                serverDir.resolve("Raven.Server.dll").toString(),
                "--Logs.Mode=Information", // user arguments stay in front of the library's
                "--Embedded.ParentProcessId=<pid>",
                "--License.Path=/etc/ravendb/license.json",
                "--License.Eula.Accepted=True",
                "--License.DisableAutoUpdate=True",
                "--License.DisableAutoUpdateFromApi=True",
                "--License.DisableLicenseSupportCheck=False",
                "--License.ThrowOnInvalidOrMissingLicense=True",
                "--Setup.Mode=None",
                "--DataDir=" + serverDir.resolve("data"),
                "--Logs.Path=" + serverDir.resolve("logs"),
                "--ServerUrl=http://127.0.0.1:9999");
    }

    @Test
    public void securedWithCertificatePathProducesTheExactArgumentList(@TempDir Path serverDir) throws Exception {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        char[] password = "changeit".toCharArray();
        Path pkcs12 = generateClientCertificate(serverDir, password);

        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");
        options.secured(pkcs12.toString(), password);

        List<String> cmd = new ArrayList<>(RavenServerRunner.buildCommandLine(options));
        normalize(cmd, "--Embedded.ParentProcessId=", "\\d+", "<pid>");
        normalize(cmd, "--Security.WellKnownCertificates.Admin=", "[0-9a-fA-F]{40}", "<sha1>");

        assertThat(cmd).containsExactly(
                "dotnet",
                serverDir.resolve("Raven.Server.dll").toString(),
                "--Embedded.ParentProcessId=<pid>",
                "--License.Eula.Accepted=False",
                "--License.DisableAutoUpdate=False",
                "--License.DisableAutoUpdateFromApi=False",
                "--License.DisableLicenseSupportCheck=True",
                "--License.ThrowOnInvalidOrMissingLicense=False",
                "--Setup.Mode=None",
                "--DataDir=" + serverDir.resolve("data"),
                "--Logs.Path=" + serverDir.resolve("logs"),
                "--Security.Certificate.Path=" + pkcs12,
                "--Security.Certificate.Password=changeit",
                "--Security.WellKnownCertificates.Admin=<sha1>",
                "--ServerUrl=https://127.0.0.1:0"); // a secured server defaults to https, not http
    }

    // As in C#, no password means no --Security.Certificate.Password argument
    @Test
    public void securedWithoutAPasswordOmitsThePasswordArgument(@TempDir Path serverDir) throws Exception {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        Path pkcs12 = generatePasswordlessClientCertificate(serverDir);

        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");
        options.secured(pkcs12.toString());

        assertThat(options.getSecurity().getCertificatePassword())
                .as("no password means null, not an empty array")
                .isNull();

        List<String> cmd = new ArrayList<>(RavenServerRunner.buildCommandLine(options));
        normalize(cmd, "--Embedded.ParentProcessId=", "\\d+", "<pid>");
        normalize(cmd, "--Security.WellKnownCertificates.Admin=", "[0-9a-fA-F]{40}", "<sha1>");

        assertThat(indexOfArgStartingWith(cmd, "--Security.Certificate.Password")).isEqualTo(-1);

        assertThat(cmd).containsExactly(
                "dotnet",
                serverDir.resolve("Raven.Server.dll").toString(),
                "--Embedded.ParentProcessId=<pid>",
                "--License.Eula.Accepted=False",
                "--License.DisableAutoUpdate=False",
                "--License.DisableAutoUpdateFromApi=False",
                "--License.DisableLicenseSupportCheck=True",
                "--License.ThrowOnInvalidOrMissingLicense=False",
                "--Setup.Mode=None",
                "--DataDir=" + serverDir.resolve("data"),
                "--Logs.Path=" + serverDir.resolve("logs"),
                "--Security.Certificate.Path=" + pkcs12,
                "--Security.WellKnownCertificates.Admin=<sha1>",
                "--ServerUrl=https://127.0.0.1:0");
    }

    @Test
    public void securedWithCertificateExecProducesTheExactArgumentList(@TempDir Path serverDir) throws Exception {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        char[] password = "changeit".toCharArray();
        KeyStore clientCertificate = load(generateClientCertificate(serverDir, password), password);

        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");
        options.secured("/usr/local/bin/get-cert", "--profile production", "ABCD1234", clientCertificate, null);

        List<String> cmd = new ArrayList<>(RavenServerRunner.buildCommandLine(options));
        normalize(cmd, "--Embedded.ParentProcessId=", "\\d+", "<pid>");
        normalize(cmd, "--Security.WellKnownCertificates.Admin=", "[0-9a-fA-F]{40}", "<sha1>");

        assertThat(cmd).containsExactly(
                "dotnet",
                serverDir.resolve("Raven.Server.dll").toString(),
                "--Embedded.ParentProcessId=<pid>",
                "--License.Eula.Accepted=False",
                "--License.DisableAutoUpdate=False",
                "--License.DisableAutoUpdateFromApi=False",
                "--License.DisableLicenseSupportCheck=True",
                "--License.ThrowOnInvalidOrMissingLicense=False",
                "--Setup.Mode=None",
                "--DataDir=" + serverDir.resolve("data"),
                "--Logs.Path=" + serverDir.resolve("logs"),
                "--Security.Certificate.Load.Exec=/usr/local/bin/get-cert",
                "--Security.Certificate.Load.Exec.Arguments=--profile production",
                "--Security.WellKnownCertificates.Admin=<sha1>",
                "--ServerUrl=https://127.0.0.1:0");
    }

    @Test
    public void frameworkVersionIsIgnoredForASelfContainedExecutable(@TempDir Path serverDir) throws IOException {
        String exeName = SystemUtils.IS_OS_WINDOWS ? "Raven.Server.exe" : "Raven.Server";
        Files.createFile(serverDir.resolve(exeName));
        ServerOptions options = optionsFor(serverDir);
        options.setFrameworkVersion("8.0.10");

        List<String> cmd = RavenServerRunner.buildCommandLine(options);

        assertThat(cmd).doesNotContain("--fx-version", "8.0.10");
    }

    // Platform-independent, so it also runs on a Linux-only CI
    @Test
    public void preEscapingIsDecidedByQuotesOrATrailingBackslashWithWhitespace() {
        assertThat(RavenServerRunner.needsPreEscaping("--DataDir=C:\\ravendb\\data")).isFalse();
        assertThat(RavenServerRunner.needsPreEscaping("--Args=--pfx cert.pfx")).isFalse();
        assertThat(RavenServerRunner.needsPreEscaping("--DataDir=C:\\ravendb\\")).isFalse(); // no whitespace

        assertThat(RavenServerRunner.needsPreEscaping("--License={\"Id\":\"a1b2\"}")).isTrue();
        assertThat(RavenServerRunner.needsPreEscaping("--DataDir=C:\\raven data\\")).isTrue();
    }

    // Platform-independent, so it also runs on a Linux-only CI
    @Test
    public void preEscapingRewritesOnlyTheArgumentsThatNeedIt() {
        List<String> args = new ArrayList<>(Arrays.asList(
                "--Args=--pfx cert.pfx",           // whitespace alone is left to ProcessBuilder
                "--DataDir=C:\\ravendb\\",           // trailing backslash without whitespace
                "--License={\"Id\":\"a1b2\"}",
                "--DataDir=C:\\raven data\\"));

        RavenServerRunner.preEscapeUnsafeArguments(args);

        assertThat(args).containsExactly(
                "--Args=--pfx cert.pfx",
                "--DataDir=C:\\ravendb\\",
                "--License={\\\"Id\\\":\\\"a1b2\\\"}",
                "\"--DataDir=C:\\raven data\\\\\"");
    }

    @Test
    public void strictQuotingModeIsSelectedOnlyByTheExactValueFalse() {
        String previousMode = System.getProperty(ALLOW_AMBIGUOUS_COMMANDS);
        String previousSpec = System.getProperty(SPEC_VERSION);
        System.setProperty(SPEC_VERSION, "11"); // isolate from the Java 8 rule asserted at the end
        try {
            System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, "false");
            assertThat(RavenServerRunner.processBuilderEscapesArgumentsItself()).isTrue();

            System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, "FALSE");
            assertThat(RavenServerRunner.processBuilderEscapesArgumentsItself()).isTrue();

            System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, "true");
            assertThat(RavenServerRunner.processBuilderEscapesArgumentsItself()).isFalse();

            System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, "yes");
            assertThat(RavenServerRunner.processBuilderEscapesArgumentsItself()).isFalse();

            System.setProperty(SPEC_VERSION, "1.8");
            System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, "false");
            assertThat(RavenServerRunner.processBuilderEscapesArgumentsItself())
                    .as("Java 8 eats embedded quotes in every mode")
                    .isFalse();
        } finally {
            restoreProperty(SPEC_VERSION, previousSpec);
            restoreProperty(ALLOW_AMBIGUOUS_COMMANDS, previousMode);
        }
    }

    @Test
    public void dllModeRunsDotnetWithDllAsFirstArgument(@TempDir Path serverDir) throws IOException {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");

        List<String> cmd = RavenServerRunner.buildCommandLine(options);

        assertThat(cmd.get(0)).isEqualTo("dotnet");
        assertThat(cmd.get(1)).contains("Raven.Server.dll"); // dll is the first argument passed to dotnet
    }

    @Test
    public void selfContainedExeRunsDirectlyWithoutDotnet(@TempDir Path serverDir) throws IOException {
        String exeName = SystemUtils.IS_OS_WINDOWS ? "Raven.Server.exe" : "Raven.Server";
        Files.createFile(serverDir.resolve(exeName));
        ServerOptions options = optionsFor(serverDir);

        List<String> cmd = RavenServerRunner.buildCommandLine(options);

        assertThat(cmd.get(0)).contains(exeName);
        assertThat(cmd).noneMatch(a -> a.equals("dotnet"));
        assertThat(cmd).noneMatch(a -> a.contains("Raven.Server.dll"));
        assertThat(cmd).doesNotContain("--fx-version");
    }

    @Test
    public void userArgumentsComeBeforeLibraryArguments(@TempDir Path serverDir) throws IOException {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.getCommandLineArgs().add("--ServerUrl=http://user-supplied:1234");

        List<String> cmd = RavenServerRunner.buildCommandLine(options);

        int userIdx = cmd.indexOf("--ServerUrl=http://user-supplied:1234");
        int libIdx = indexOfArgStartingWith(cmd, "--ServerUrl=http://127.0.0.1");

        assertThat(userIdx).isGreaterThanOrEqualTo(0);
        // Library --ServerUrl is emitted after the user's, so the server (last-key-wins) uses the library value.
        assertThat(libIdx).isGreaterThan(userIdx);
    }

    @Test
    public void licensingArgumentsUseCSharpDefaults(@TempDir Path serverDir) throws IOException {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);

        List<String> cmd = RavenServerRunner.buildCommandLine(options);

        assertThat(cmd).contains(
                "--License.Eula.Accepted=False",
                "--License.DisableLicenseSupportCheck=True");
    }

    @Test
    public void licenseValueIsEmittedWhenSet(@TempDir Path serverDir) throws IOException {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.getLicensing().setLicense("{\"Id\":\"abc\"}");

        List<String> cmd = RavenServerRunner.buildCommandLine(options);

        // deliberately a value without whitespace: on Windows a license carrying a space comes back
        // from buildCommandLine fully quoted, so a startsWith() assertion would not hold. What the
        // child process actually receives is asserted in licenseJsonSurvivesProcessBuilderIntact.
        assertThat(indexOfArgStartingWith(cmd, "--License=")).isGreaterThanOrEqualTo(0);
        assertThat(indexOfArgStartingWith(cmd, "--License.Path=")).isEqualTo(-1);
    }

    /**
     * Regression test for licensing being unusable on Windows: ProcessBuilder hands an argument with
     * embedded double quotes to the OS untouched, and the child's C runtime then eats those quotes as
     * delimiters - so a license JSON lost its quotes and split at the space inside {@code "Name"},
     * and the server died with "Unrecognized command or argument". Every real license has a name with
     * a space in it. Asserts on the argv the child receives, which is the only thing that matters
     * here, and holds on both platforms.
     */
    @Test
    public void licenseJsonSurvivesProcessBuilderIntact(@TempDir Path serverDir) throws Exception {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");

        options.getLicensing().setLicense(LICENSE_JSON);

        List<String> cmd = RavenServerRunner.buildCommandLine(options);
        List<String> received = runArgPrinter(cmd.subList(1, cmd.size()));

        assertThat(received).contains("--License=" + LICENSE_JSON);
    }

    /**
     * The counterpart to the test above, for ProcessBuilder's other Windows mode. The strict mode
     * selected by {@code -Djdk.lang.Process.allowAmbiguousCommands=false} (or by a SecurityManager)
     * escapes embedded quotes itself, so pre-escaping there doubles up: a license with a space fails
     * process creation outright with "Malformed argument has embedded quote", and one without
     * silently reaches the server with backslashes left in the JSON. The two modes need opposite
     * treatment, so both need a round trip.
     * <p>
     * The JDK reads that property on every {@link ProcessBuilder} start, so flipping it here is
     * enough to exercise the strict path - no second-level fork required.
     */
    @Test
    public void licenseJsonSurvivesProcessBuilderInStrictQuotingMode(@TempDir Path serverDir) throws Exception {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");
        options.getLicensing().setLicense(LICENSE_JSON);

        String previous = System.getProperty(ALLOW_AMBIGUOUS_COMMANDS);
        System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, "false");
        try {
            List<String> cmd = RavenServerRunner.buildCommandLine(options);
            List<String> received = runArgPrinter(cmd.subList(1, cmd.size()));

            assertThat(received).contains("--License=" + LICENSE_JSON);
        } finally {
            restoreProperty(ALLOW_AMBIGUOUS_COMMANDS, previous);
        }
    }

    /**
     * Only the exact value {@code false} selects the strict mode - {@code true} and anything else
     * leave ProcessBuilder in its default mode, so the pre-escape must still be applied. Guards
     * against simplifying the condition to "the property is set".
     */
    @Test
    public void licenseJsonSurvivesWhenAmbiguousCommandsAreExplicitlyAllowed(@TempDir Path serverDir) throws Exception {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");
        options.getLicensing().setLicense(LICENSE_JSON);

        String previous = System.getProperty(ALLOW_AMBIGUOUS_COMMANDS);
        System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, "true");
        try {
            List<String> cmd = RavenServerRunner.buildCommandLine(options);
            List<String> received = runArgPrinter(cmd.subList(1, cmd.size()));

            assertThat(received).contains("--License=" + LICENSE_JSON);
        } finally {
            restoreProperty(ALLOW_AMBIGUOUS_COMMANDS, previous);
        }
    }

    /**
     * Java 8 eats embedded quotes in <i>every</i> mode - its strict path predates the self-escaping
     * verification - so the pre-escape must never be skipped there. On a real Java 8 JVM
     * licenseJsonSurvivesProcessBuilderInStrictQuotingMode already covers this by round trip; this
     * pins the decision on newer JVMs, where that combination cannot be reproduced because 11+
     * strict mode genuinely does self-escape. Asserts on the decision rather than the argv for the
     * same reason.
     */
    @Test
    public void java8NeverSkipsEscapingEvenInStrictQuotingMode(@TempDir Path serverDir) throws IOException {
        assumeTrue(SystemUtils.IS_OS_WINDOWS, "the escape pass is Windows-only");

        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");
        options.getLicensing().setLicense(LICENSE_JSON);

        String previousMode = System.getProperty(ALLOW_AMBIGUOUS_COMMANDS);
        String previousSpec = System.getProperty(SPEC_VERSION);
        System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, "false");
        System.setProperty(SPEC_VERSION, "1.8");
        try {
            List<String> cmd = RavenServerRunner.buildCommandLine(options);

            // LICENSE_JSON contains a space, so the escaped form is the whole argument in quotes
            assertThat(indexOfArgStartingWith(cmd, "\"--License="))
                    .as("license must still be escaped on Java 8 in strict mode")
                    .isGreaterThanOrEqualTo(0);
            assertThat(cmd).doesNotContain("--License=" + LICENSE_JSON);
        } finally {
            restoreProperty(SPEC_VERSION, previousSpec);
            restoreProperty(ALLOW_AMBIGUOUS_COMMANDS, previousMode);
        }
    }

    /**
     * The same Windows quote mangling hits every other argument that can legitimately carry a double
     * quote - a certificate password, exec arguments, or a license a user smuggles in through
     * commandLineArgs - so the fix is applied per-argument rather than to the license alone.
     */
    @Test
    public void quotedUserArgumentSurvivesProcessBuilderIntact(@TempDir Path serverDir) throws Exception {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");

        String userArg = "--Security.Certificate.Load.Exec.Arguments=--pfx \"My Certs/cert.pfx\"";
        options.getCommandLineArgs().add(userArg);

        List<String> cmd = RavenServerRunner.buildCommandLine(options);
        List<String> received = runArgPrinter(cmd.subList(1, cmd.size()));

        assertThat(received).contains(userArg);
    }

    /**
     * A value that both contains whitespace and ends with backslashes is the other shape Windows
     * mangles: ProcessBuilder wraps it in quotes, and on Java 8 doubles at most one of the trailing
     * backslashes - so the last one escapes the closing quote and the value swallows the argument
     * after it (verified on 8u202: {@code --DataDir2=C:\raven data\" --tail=marker} arrived as one
     * argument). Java 9+ doubles them all itself. Pins the decision, because the round trip below
     * cannot fail on a modern JVM.
     */
    @Test
    public void trailingBackslashWithWhitespaceIsPreQuotedOnWindows(@TempDir Path serverDir) throws IOException {
        assumeTrue(SystemUtils.IS_OS_WINDOWS, "the escape pass is Windows-only");

        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");
        options.getCommandLineArgs().add(TRAILING_BACKSLASH_ARG);

        String previous = System.getProperty(ALLOW_AMBIGUOUS_COMMANDS);
        System.clearProperty(ALLOW_AMBIGUOUS_COMMANDS); // ProcessBuilder's default mode
        try {
            List<String> cmd = RavenServerRunner.buildCommandLine(options);

            assertThat(indexOfArgStartingWith(cmd, "\"--Security.Certificate.Exec.Path="))
                    .as("a whitespace + trailing backslash value must be pre-quoted")
                    .isGreaterThanOrEqualTo(0);
            assertThat(cmd).doesNotContain(TRAILING_BACKSLASH_ARG);
        } finally {
            restoreProperty(ALLOW_AMBIGUOUS_COMMANDS, previous);
        }
    }

    @Test
    public void trailingBackslashWithWhitespaceSurvivesProcessBuilderIntact(@TempDir Path serverDir) throws Exception {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");
        options.getCommandLineArgs().add(TRAILING_BACKSLASH_ARG);

        List<String> cmd = RavenServerRunner.buildCommandLine(options);
        List<String> received = runArgPrinter(cmd.subList(1, cmd.size()));

        // the argument itself, and the one behind it, both reach the child intact
        assertThat(received).contains(TRAILING_BACKSLASH_ARG, "--Setup.Mode=None");
    }

    /**
     * The counterpart in strict mode, which doubles trailing backslashes itself: pre-escaping there
     * doubles them twice over, and the child receives four backslashes instead of two.
     */
    @Test
    public void trailingBackslashWithWhitespaceSurvivesInStrictQuotingMode(@TempDir Path serverDir) throws Exception {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");
        options.getCommandLineArgs().add(TRAILING_BACKSLASH_ARG);

        String previous = System.getProperty(ALLOW_AMBIGUOUS_COMMANDS);
        System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, "false");
        try {
            List<String> cmd = RavenServerRunner.buildCommandLine(options);
            List<String> received = runArgPrinter(cmd.subList(1, cmd.size()));

            assertThat(received).contains(TRAILING_BACKSLASH_ARG, "--Setup.Mode=None");
        } finally {
            restoreProperty(ALLOW_AMBIGUOUS_COMMANDS, previous);
        }
    }

    @Test
    public void licenseAndLicensePathAreMutuallyExclusive(@TempDir Path serverDir) throws IOException {
        Files.createFile(serverDir.resolve("Raven.Server.dll"));
        ServerOptions options = optionsFor(serverDir);
        options.getLicensing().setLicense("{\"Id\":\"abc\"}");
        options.getLicensing().setLicensePath("/path/license.json");

        assertThatThrownBy(() -> RavenServerRunner.buildCommandLine(options))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Regression test for the double-escaping bug: buildCommandLine must emit raw values, because
     * ProcessBuilder takes a List&lt;String&gt; and does platform-correct argv construction itself.
     * Escaping here used to split whitespace values on Windows and leak literal quote characters on
     * Unix. Asserts on the argv a real child process receives, not just on the built list.
     */
    @Test
    public void whitespaceArgumentsSurviveProcessBuilderUnsplit(@TempDir Path tempDir) throws Exception {
        Path serverDir = tempDir.resolve("My Raven Dir");
        Files.createDirectories(serverDir);
        Files.createFile(serverDir.resolve("Raven.Server.dll"));

        ServerOptions options = optionsFor(serverDir);
        options.setDotNetPath("dotnet");

        List<String> cmd = RavenServerRunner.buildCommandLine(options);

        String expectedDll = serverDir.resolve("Raven.Server.dll").toString();
        String expectedDataDir = "--DataDir=" + serverDir.resolve("data");
        String expectedLogsPath = "--Logs.Path=" + serverDir.resolve("logs");

        // the built list carries raw, unquoted values...
        assertThat(cmd.get(1)).isEqualTo(expectedDll);
        assertThat(cmd).contains(expectedDataDir, expectedLogsPath);

        // ...and each one reaches the child process as a single argv entry.
        List<String> received = runArgPrinter(cmd.subList(1, cmd.size()));

        assertThat(received).contains(expectedDll, expectedDataDir, expectedLogsPath);
    }

    /** Spawns a real JVM with the given arguments and returns the argv it actually received. */
    private static List<String> runArgPrinter(List<String> args) throws Exception {
        String javaExe = SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java";
        String classPath = Paths.get(
                ArgPrinter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();

        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", javaExe).toString());
        command.add("-cp");
        command.add(classPath);
        command.add(ArgPrinter.class.getName());
        command.addAll(args);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        List<String> received = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                received.add(line);
            }
        }

        assertThat(process.waitFor()).isZero();
        return received;
    }
}
