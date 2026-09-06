package net.ravendb.embedded;

/**
 * Quoting rules for a single Windows command-line string. Used only to render human-readable
 * diagnostics (the {@code "Command was: "} message); it must <b>not</b> be applied to arguments
 * passed to {@link ProcessBuilder}, which escapes them itself - see
 * {@link RavenServerRunner#buildCommandLine(ServerOptions)}.
 */
public class CommandLineArgumentEscaper {

    public static String escapeAndConcatenate(Iterable<String> args) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String arg : args) {
            if (!first) {
                sb.append(' ');
            }
            sb.append(escapeSingleArg(arg));
            first = false;
        }
        return sb.toString();
    }

    public static String escapeSingleArg(String arg) {
        if (arg == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        boolean needsQuotes = containsWhitespace(arg);
        boolean isQuoted = needsQuotes || isSurroundedWithQuotes(arg);

        if (needsQuotes) {
            sb.append('"');
        }

        for (int i = 0; i < arg.length(); ++i) {
            int backslashes = 0;

            while (i < arg.length() && arg.charAt(i) == '\\') {
                backslashes++;
                i++;
            }

            if (i == arg.length() && isQuoted) {
                appendRepeated(sb, '\\', 2 * backslashes);
            } else if (i == arg.length()) {
                appendRepeated(sb, '\\', backslashes);
            } else if (arg.charAt(i) == '"') {
                appendRepeated(sb, '\\', (2 * backslashes) + 1);
                sb.append('"');
            } else {
                appendRepeated(sb, '\\', backslashes);
                sb.append(arg.charAt(i));
            }
        }

        if (needsQuotes) {
            sb.append('"');
        }

        return sb.toString();
    }

    private static void appendRepeated(StringBuilder sb, char c, int count) {
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
    }

    private static boolean isSurroundedWithQuotes(String argument) {
        if (argument.length() <= 1) {
            return false;
        }

        return argument.charAt(0) == '"' && argument.charAt(argument.length() - 1) == '"';
    }

    private static boolean containsWhitespace(String argument) {
        return argument.indexOf(' ') >= 0 || argument.indexOf('\t') >= 0 || argument.indexOf('\n') >= 0;
    }
}
