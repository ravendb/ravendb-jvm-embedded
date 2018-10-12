package net.ravendb.embedded;

import net.ravendb.client.exceptions.RavenException;
import net.ravendb.client.util.CertificateUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class RavenServerRunner {

    public static Process run(ServerOptions options) {
        if (StringUtils.isBlank(options.getTargetServerLocation())) {
            throw new IllegalArgumentException("targetServerLocation cannot be null or whitespace");
        }

        if (StringUtils.isBlank(options.getDataDirectory())) {
            throw new IllegalArgumentException("dataDirectory cannot be null or whitespace");
        }

        if (StringUtils.isBlank(options.getLogsPath())) {
            throw new IllegalArgumentException("logsPath cannot be null or whitespace");
        }

        String serverDllPath = Paths.get(options.getTargetServerLocation(), "Raven.Server.dll").toString();
        boolean serverDllFound = new File(serverDllPath).exists();

        if (!serverDllFound) {
            throw new RavenException("Server file was not found: " + serverDllPath);
        }

        if (StringUtils.isBlank(options.getDotNetPath())) {
            throw new IllegalArgumentException("dotNetPath cannot be null or whitespace");
        }

        List<String> commandLineArgs = new ArrayList<>();

        commandLineArgs.add("--Embedded.ParentProcessId=" + getProcessId("0"));
        commandLineArgs.add("--License.Eula.Accepted=" + (options.isAcceptEula() ? "true" : "false"));
        commandLineArgs.add("--Setup.Mode=None");

        commandLineArgs.add("--DataDir=" + CommandLineArgumentEscaper.escapeSingleArg(options.getDataDirectory()));
        commandLineArgs.add("--Logs.Path=" + CommandLineArgumentEscaper.escapeSingleArg(options.getLogsPath()));

        if (options.getSecurity() != null) {
            if (StringUtils.isBlank(options.getServerUrl())) {
                options.setServerUrl("https://127.0.0.1:0");
            }

            if (options.getSecurity().getCertificatePath() != null) {
                commandLineArgs.add("--Security.Certificate.Path=" + CommandLineArgumentEscaper.escapeSingleArg(options.getSecurity().getCertificatePath()));

                if (options.getSecurity().getCertificatePassword() != null) {
                    commandLineArgs.add("--Security.Certificate.Password="
                            + CommandLineArgumentEscaper.escapeSingleArg(String.valueOf(options.getSecurity().getCertificatePassword())));
                }
            } else {
                commandLineArgs.add("--Security.Certificate.Exec=" + CommandLineArgumentEscaper.escapeSingleArg(options.getSecurity().getCertificateExec()));
                commandLineArgs.add("--Security.Certificate.Exec.Arguments=" + CommandLineArgumentEscaper.escapeSingleArg(options.getSecurity().getCertificateArguments()));
            }

            commandLineArgs.add("--Security.WellKnownCertificates.Admin="
                    + CommandLineArgumentEscaper.escapeSingleArg(
                            CertificateUtils.extractThumbprintFromCertificate(
                                    options.getSecurity().getClientCertificate())));
        } else {
            if (StringUtils.isBlank(options.getServerUrl())) {
                options.setServerUrl("http://127.0.0.1:0");
            }
        }

        commandLineArgs.add("--ServerUrl=" + options.getServerUrl());
        commandLineArgs.add(0, CommandLineArgumentEscaper.escapeSingleArg(serverDllPath));

        if (StringUtils.isNotBlank(options.getFrameworkVersion())) {
            commandLineArgs.addAll(0, Arrays.asList("--fx-version", options.getFrameworkVersion()));
        }

        commandLineArgs.add(0, options.getDotNetPath());

        ProcessBuilder processBuilder = new ProcessBuilder(commandLineArgs);
        Process process;
        try {
            process = processBuilder.start();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to execute server. " + System.lineSeparator()
                    + "Command was: " + System.lineSeparator()
                    + (ObjectUtils.firstNonNull(processBuilder.directory().getAbsolutePath(), Paths.get("").toAbsolutePath().toString()))
                    + "> "
                    + String.join(" ", processBuilder.command()), e);
        }

        return process;
    }

    private static String getProcessId(final String fallback) {
        final String jvmName = ManagementFactory.getRuntimeMXBean().getName();
        final int index = jvmName.indexOf('@');

        if (index < 1) {
            return fallback;
        }

        try {
            return Long.toString(Long.parseLong(jvmName.substring(0, index)));
        } catch (NumberFormatException e) {
            // ignore
        }
        return fallback;
    }
}
