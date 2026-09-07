package net.ravendb.embedded;

import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RavenServerRunnerTest {

    private static final String ALLOW_AMBIGUOUS_COMMANDS = "jdk.lang.Process.allowAmbiguousCommands";

    private static final String LICENSE_JSON =
            "{\"Id\":\"a1b2\",\"Name\":\"Bogus Corp Ltd\",\"Keys\":[\"AAAABBBBCCCC\"]}";

    private static ServerOptions optionsFor(Path serverDir) {
        ServerOptions options = new ServerOptions();
        options.setTargetServerLocation(serverDir.toString());
        options.setDataDirectory(serverDir.resolve("data").toString());
        options.setLogsPath(serverDir.resolve("logs").toString());
        options.setFrameworkVersion(null); // avoid invoking the `dotnet --info` probe in unit tests
        return options;
    }

    private static int indexOfArgStartingWith(List<String> args, String prefix) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
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
            if (previous == null) {
                System.clearProperty(ALLOW_AMBIGUOUS_COMMANDS);
            } else {
                System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, previous);
            }
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
            if (previous == null) {
                System.clearProperty(ALLOW_AMBIGUOUS_COMMANDS);
            } else {
                System.setProperty(ALLOW_AMBIGUOUS_COMMANDS, previous);
            }
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
