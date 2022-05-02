package net.ravendb.embedded;

import net.ravendb.client.primitives.Tuple;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class RuntimeFrameworkVersionMatcher {

    private final static String WILDCARD = "x";

    public final static char GREATER_OR_EQUAL = '+';

    public static String match(ServerOptions options) {
        if (!needsMatch(options)) {
            return options != null ? options.getFrameworkVersion() : null;
        }

        RuntimeFrameworkVersion runtime = new RuntimeFrameworkVersion(options.getFrameworkVersion());
        List<RuntimeFrameworkVersion> runtimes = getFrameworkVersions(options);

        return match(runtime, runtimes);
    }

    public static String match(RuntimeFrameworkVersion runtime, List<RuntimeFrameworkVersion> runtimes) {

        List<RuntimeFrameworkVersion> sortedRuntimes = runtimes.stream().sorted(
                Comparator.comparingInt(RuntimeFrameworkVersion::getMajor)
                        .thenComparingInt(RuntimeFrameworkVersion::getMinor)
                        .thenComparingInt(RuntimeFrameworkVersion::getPatch)
                        .reversed()
        ).collect(Collectors.toList());

        for (RuntimeFrameworkVersion version : sortedRuntimes) {
            if (runtime.match(version)) {
                return version.toString();
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Could not find a matching runtime for '")
                .append(runtime)
                .append("'. Available runtimes:");
        sb.append(System.lineSeparator());
        for (RuntimeFrameworkVersion r : sortedRuntimes) {

            sb.append("- ").append(r);
        }

        throw new IllegalStateException(sb.toString());
    }

    private static boolean needsMatch(ServerOptions options) {
        if (options == null || StringUtils.isBlank(options.getFrameworkVersion())) {
            return false;
        }

        String frameworkVersionAsString = options.getFrameworkVersion().toLowerCase();
        if (!frameworkVersionAsString.contains(WILDCARD) && !frameworkVersionAsString.contains(String.valueOf(GREATER_OR_EQUAL))) { // no wildcards && no greaterOrEqual
            return false;
        }

        return true;
    }

    private static List<RuntimeFrameworkVersion> getFrameworkVersions(ServerOptions options) {
        if (StringUtils.isBlank(options.getDotNetPath())) {
            throw new IllegalStateException();
        }


        ProcessBuilder processBuilder = new ProcessBuilder(options.getDotNetPath(), "--info");

        Process process = null;

        try {
            process = processBuilder.start();


            String line = null;
            boolean insideRuntimes = false;
            List<String> runtimeLines = new ArrayList<>();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith(".NET runtimes installed:") || line.startsWith(".NET Core runtimes installed:")) {
                    insideRuntimes = true;
                    continue;
                }

                if (insideRuntimes && line.startsWith("Microsoft.NETCore.App")) {
                    runtimeLines.add(line);
                    continue;
                }
            }

            List<RuntimeFrameworkVersion> runtimes = new ArrayList<>();
            for (String runtimeLine : runtimeLines) { // Microsoft.NETCore.App 5.0.2 [C:\Program Files\dotnet\shared\Microsoft.NETCore.App]
                String[] values = runtimeLine.split(" ");
                if (values.length < 2) {
                    throw new IllegalStateException("Invalid runtime line. Expected 'Microsoft.NETCore.App x.x.x', but was '" + runtimeLine + "'");
                }

                runtimes.add(new RuntimeFrameworkVersion(values[1]));
            }

            return runtimes;

        } catch (Exception e) {
            throw new IllegalStateException("Unable to execute dotnet to retrieve list of installed runtimes", e);

        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    public static class RuntimeFrameworkVersion {
        private final static String SEPARATORS = ".";
        private final static String SUFFIX_SEPARATOR = "-";

        private Integer major;
        private Integer minor;
        private Integer patch;
        private MatchingType patchMatchingType;
        private String suffix;

        public RuntimeFrameworkVersion(String frameworkVersion) {

            frameworkVersion = frameworkVersion.toLowerCase();

            List<String> suffixes = Arrays.stream(frameworkVersion.split(SUFFIX_SEPARATOR)).filter(StringUtils::isNotBlank).collect(Collectors.toList());

            if (suffixes.size() != 1) {
                frameworkVersion = suffixes.get(0);
                suffix = suffixes.stream().skip(1).collect(Collectors.joining(SUFFIX_SEPARATOR));
            }

            List<String> versions = Arrays.stream(StringUtils.split(frameworkVersion, SEPARATORS)).filter(StringUtils::isNotBlank).collect(Collectors.toList());
            for (int i = 0; i < versions.size(); i++) {
                String version = versions.get(i).trim();
                if (!version.contains(WILDCARD)) {
                    Tuple<Integer, MatchingType> tuple = parse(version);
                    set(i, version, tuple.first, tuple.second);
                    continue;
                }

                if (!version.equals(WILDCARD)) {
                    throw new IllegalStateException("Wildcard character must be a sole part of the version string, but was '" + version + "'."); // e.g. 3x, x7, etc
                }

                set(i, null, null, MatchingType.EQUAL);
            }
        }

        public Integer getMajor() {
            return major;
        }

        public Integer getMinor() {
            return minor;
        }

        public Integer getPatch() {
            return patch;
        }

        public void setPatch(Integer patch) {
            this.patch = patch;
        }

        public MatchingType getPatchMatchingType() {
            return patchMatchingType;
        }

        public String getSuffix() {
            return suffix;
        }

        private static String toStringInterval(Integer number, MatchingType matchingType) {
            if (number == null) {
                return WILDCARD;
            }

            switch (matchingType) {
                case EQUAL:
                    return number.toString();
                case GREATER_OR_EQUAL:
                    return number.toString() + GREATER_OR_EQUAL;
                default:
                    throw new IllegalArgumentException("Invalid matching type: " + matchingType);
            }
        }

        @Override
        public String toString() {
            String s1 = toStringInterval(major, MatchingType.EQUAL);
            String s2 = toStringInterval(minor, MatchingType.EQUAL);
            String s3 = toStringInterval(patch, patchMatchingType);

            String version = s1 + "." + s2 + "." + s3;

            if (suffix != null) {
                version += SUFFIX_SEPARATOR + suffix;
            }

            return version;
        }

        private static Tuple<Integer, MatchingType> parse(String value) {
            MatchingType matchingType = MatchingType.EQUAL;

            String valueToParse = value;

            char lastChar = valueToParse.charAt(valueToParse.length() - 1);
            if (lastChar == GREATER_OR_EQUAL) {
                matchingType = MatchingType.GREATER_OR_EQUAL;
                valueToParse = valueToParse.substring(0, valueToParse.length() - 1);
            }

            int valueAsInt = Integer.parseInt(valueToParse);

            return Tuple.create(valueAsInt, matchingType);
        }

        private void set(int i, String valueAsString, Integer value, MatchingType matchingType) {
            switch (i) {
                case 0:
                    assertMatchingType("major", valueAsString, MatchingType.EQUAL, matchingType);
                    major = value;
                    break;
                case 1:
                    assertMatchingType("minor", valueAsString, MatchingType.EQUAL, matchingType);
                    minor = value;
                    break;
                case 2:
                    assertMatchingType("patch", valueAsString, null, matchingType);
                    patch = value;
                    patchMatchingType = matchingType;
                    break;
                default:
                    throw new IllegalArgumentException();
            }
        }

        private void assertMatchingType(String fieldName, String valueAsString, MatchingType expectedMatchingType, MatchingType matchingType) {
            if (suffix != null && matchingType != MatchingType.EQUAL) {
                throw new IllegalStateException(
                        "Cannot set '" + fieldName + "' with value '" + valueAsString
                                + "' because '" + matchingTypeToString(matchingType)
                                + "' is not allowed when suffix ('" + suffix + "') is set.");
            }

            if (expectedMatchingType != null && expectedMatchingType != matchingType) {
                throw new IllegalStateException(
                        "Cannot set '" + fieldName + "' with value '" + valueAsString
                                + "' because '" + matchingTypeToString(matchingType) + "' is not allowed.");
            }
        }

        private static String matchingTypeToString(MatchingType matchingType) {
            switch (matchingType) {
                case EQUAL:
                    return "";
                case GREATER_OR_EQUAL:
                    return String.valueOf(GREATER_OR_EQUAL);
                default:
                    throw new IllegalArgumentException("Illegal matching type: " + matchingType);
            }
        }

        public boolean match(RuntimeFrameworkVersion version) {
            if (major != null && !major.equals(version.getMajor())) {
                return false;
            }

            if (minor != null && !minor.equals(version.getMinor())) {
                return false;
            }

            if (patch != null) {
                switch (patchMatchingType) {
                    case EQUAL:
                        if (!patch.equals(version.getPatch())) {
                            return false;
                        }
                        break;
                    case GREATER_OR_EQUAL:
                        if (patch > version.getPatch()) {
                            return false;
                        }
                        break;
                    default:
                        throw new IllegalArgumentException();
                }
            }

            if (!StringUtils.equals(suffix, version.getSuffix())) {
                return false;
            }

            return true;
        }
    }


    public enum MatchingType {
        EQUAL,
        GREATER_OR_EQUAL
    }
}
