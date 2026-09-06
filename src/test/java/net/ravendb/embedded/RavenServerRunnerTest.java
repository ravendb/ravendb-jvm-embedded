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

        assertThat(indexOfArgStartingWith(cmd, "--License=")).isGreaterThanOrEqualTo(0);
        assertThat(indexOfArgStartingWith(cmd, "--License.Path=")).isEqualTo(-1);
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
