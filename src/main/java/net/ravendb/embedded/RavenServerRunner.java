package net.ravendb.embedded;

import net.ravendb.client.exceptions.RavenException;
import net.ravendb.client.util.CertificateUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class RavenServerRunner {

    public static Process run(ServerOptions options) {
        List<String> commandLineArgs = buildCommandLine(options);

        ProcessBuilder processBuilder = new ProcessBuilder(commandLineArgs);
        Process process;
        try {
            process = processBuilder.start();
        } catch (Exception e) {
            String path = Paths.get("").toAbsolutePath().toString();
            if (processBuilder.directory() != null) {
                path = processBuilder.directory().getAbsolutePath();
            }

            throw new IllegalStateException("Unable to execute server. " + System.lineSeparator()
                    + "Command was: " + System.lineSeparator()
                    + path
                    + "> "
                    + CommandLineArgumentEscaper.escapeAndConcatenate(processBuilder.command()), e);
        }

        return process;
    }

    static List<String> buildCommandLine(ServerOptions options) {
        if (StringUtils.isBlank(options.getTargetServerLocation())) {
            throw new IllegalArgumentException("targetServerLocation cannot be null or whitespace");
        }

        if (StringUtils.isBlank(options.getDataDirectory())) {
            throw new IllegalArgumentException("dataDirectory cannot be null or whitespace");
        }

        if (StringUtils.isBlank(options.getLogsPath())) {
            throw new IllegalArgumentException("logsPath cannot be null or whitespace");
        }

        ExecAndFirstArgument execAndFirstArgument = getExecAndFirstArgument(options);
        String exec = execAndFirstArgument.exec;
        String firstArgument = execAndFirstArgument.firstArgument;

        List<String> commandLineArgs = new ArrayList<>(options.getCommandLineArgs());

        commandLineArgs.add("--Embedded.ParentProcessId=" + getProcessId("0"));

        LicensingOptions licensing = options.getLicensing();
        if (licensing != null) {
            if (StringUtils.isNotBlank(licensing.getLicense()) && StringUtils.isNotBlank(licensing.getLicensePath())) {
                throw new IllegalArgumentException("Only one of License options 'License' or 'LicensePath' should be specified");
            }

            if (StringUtils.isNotBlank(licensing.getLicense())) {
                commandLineArgs.add("--License=" + licensing.getLicense());
            } else if (StringUtils.isNotBlank(licensing.getLicensePath())) {
                commandLineArgs.add("--License.Path=" + licensing.getLicensePath());
            }

            commandLineArgs.add("--License.Eula.Accepted=" + toCsharpBool(licensing.isEulaAccepted()));
            commandLineArgs.add("--License.DisableAutoUpdate=" + toCsharpBool(licensing.isDisableAutoUpdate()));
            commandLineArgs.add("--License.DisableAutoUpdateFromApi=" + toCsharpBool(licensing.isDisableAutoUpdateFromApi()));
            commandLineArgs.add("--License.DisableLicenseSupportCheck=" + toCsharpBool(licensing.isDisableLicenseSupportCheck()));
            commandLineArgs.add("--License.ThrowOnInvalidOrMissingLicense=" + toCsharpBool(licensing.isThrowOnInvalidOrMissingLicense()));
        }

        commandLineArgs.add("--Setup.Mode=None");

        commandLineArgs.add("--DataDir=" + options.getDataDirectory());
        commandLineArgs.add("--Logs.Path=" + options.getLogsPath());

        if (options.getSecurity() != null) {
            if (StringUtils.isBlank(options.getServerUrl())) {
                options.setServerUrl("https://127.0.0.1:0");
            }

            if (options.getSecurity().getCertificatePath() != null) {
                commandLineArgs.add("--Security.Certificate.Path=" + options.getSecurity().getCertificatePath());

                if (options.getSecurity().getCertificatePassword() != null) {
                    commandLineArgs.add("--Security.Certificate.Password="
                            + String.valueOf(options.getSecurity().getCertificatePassword()));
                }
            } else {
                commandLineArgs.add("--Security.Certificate.Load.Exec=" + options.getSecurity().getCertificateExec());
                commandLineArgs.add("--Security.Certificate.Load.Exec.Arguments=" + options.getSecurity().getCertificateArguments());
            }

            commandLineArgs.add("--Security.WellKnownCertificates.Admin="
                    + CertificateUtils.extractThumbprintFromCertificate(
                            options.getSecurity().getClientCertificate()));
        } else {
            if (StringUtils.isBlank(options.getServerUrl())) {
                options.setServerUrl("http://127.0.0.1:0");
            }
        }

        commandLineArgs.add("--ServerUrl=" + options.getServerUrl());

        if (firstArgument != null) {
            commandLineArgs.add(0, firstArgument);

            if (StringUtils.isNotBlank(options.getFrameworkVersion())) {
                String frameworkVersion = RuntimeFrameworkVersionMatcher.match(options);
                commandLineArgs.addAll(0, Arrays.asList("--fx-version", frameworkVersion));
            }
        }

        escapeEmbeddedQuotesOnWindows(commandLineArgs);

        commandLineArgs.add(0, exec);

        return commandLineArgs;
    }

    private static void escapeEmbeddedQuotesOnWindows(List<String> args) {
        if (!SystemUtils.IS_OS_WINDOWS || processBuilderEscapesQuotesItself()) {
            return;
        }

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg != null && arg.indexOf('"') >= 0) {
                args.set(i, CommandLineArgumentEscaper.escapeSingleArg(arg));
            }
        }
    }

    private static boolean processBuilderEscapesQuotesItself() {
        // Java 8 eats embedded quotes in every mode
        if ("1.8".equals(System.getProperty("java.specification.version"))) {
            return false;
        }
        
        String value = System.getProperty("jdk.lang.Process.allowAmbiguousCommands");
        if (value == null) {
            return isSecurityManagerPresent();
        }

        return "false".equalsIgnoreCase(value);
    }

    private static boolean isSecurityManagerPresent() {
        // System.getSecurityManager() is terminally deprecated and always returns null from JDK 24.
        // Called reflectively so a future removal cannot break this class.
        try {
            return System.class.getMethod("getSecurityManager").invoke(null) != null;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static ExecAndFirstArgument getExecAndFirstArgument(ServerOptions options) {
        String nativeExecName = SystemUtils.IS_OS_WINDOWS ? "Raven.Server.exe" : "Raven.Server";
        String nativeExec = Paths.get(options.getTargetServerLocation(), nativeExecName).toString();

        if (new File(nativeExec).exists()) {
            return new ExecAndFirstArgument(nativeExec, null);
        }

        String serverDllPath = Paths.get(options.getTargetServerLocation(), "Raven.Server.dll").toString();
        boolean serverDllFound = new File(serverDllPath).exists();

        if (!serverDllFound) {
            if (StringUtils.equalsIgnoreCase(options.getTargetServerLocation(), ServerOptions.DEFAULT_SERVER_LOCATION)) {
                String altServerDllPath = Paths.get(ServerOptions.ALT_SERVER_LOCATION, "Raven.Server.dll").toString();
                if (new File(altServerDllPath).exists()) {
                    serverDllFound = true;
                    serverDllPath = altServerDllPath;
                }
            }

            if (!serverDllFound) {
                throw new RavenException("Server file was not found: " + serverDllPath);
            }
        }

        if (StringUtils.isBlank(options.getDotNetPath())) {
            throw new IllegalArgumentException("dotNetPath cannot be null or whitespace");
        }

        return new ExecAndFirstArgument(options.getDotNetPath(), serverDllPath);
    }

    private static final class ExecAndFirstArgument {
        final String exec;
        final String firstArgument;

        ExecAndFirstArgument(String exec, String firstArgument) {
            this.exec = exec;
            this.firstArgument = firstArgument;
        }
    }

    private static String toCsharpBool(boolean value) {
        return value ? "True" : "False";
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
        }
        return fallback;
    }
}
