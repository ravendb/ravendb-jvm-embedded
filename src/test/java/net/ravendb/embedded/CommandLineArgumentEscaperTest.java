package net.ravendb.embedded;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CommandLineArgumentEscaperTest {

    private static final String CR = String.valueOf((char) 0x0D);     // carriage return
    private static final String NBSP = String.valueOf((char) 0xA0);   // no-break space
    private static final String VT = String.valueOf((char) 0x0B);     // vertical tab
    private static final String FF = String.valueOf((char) 0x0C);     // form feed

    @Test
    public void plainArgIsUnchanged() {
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("--DataDir=data"))
                .isEqualTo("--DataDir=data");
    }

    @Test
    public void whitespaceGetsQuoted() {
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("C:\\Program Files\\RavenDB"))
                .isEqualTo("\"C:\\Program Files\\RavenDB\"");
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a\tb"))
                .isEqualTo("\"a\tb\"");
    }

    @Test
    public void embeddedQuoteIsEscaped() {
        // a"b -> a\"b (no surrounding quotes because no whitespace)
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a\"b"))
                .isEqualTo("a\\\"b");
    }

    @Test
    public void embeddedQuoteWithWhitespaceIsQuotedAndEscaped() {
        // a "b -> "a \"b"
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a \"b"))
                .isEqualTo("\"a \\\"b\"");
    }

    @Test
    public void trailingBackslashUnquotedIsLeftAsIs() {
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("path\\"))
                .isEqualTo("path\\");
    }

    @Test
    public void trailingBackslashQuotedIsDoubled() {
        // "dir with space\" -> "dir with space\\" (the trailing backslash doubled inside quotes)
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("dir with space\\"))
                .isEqualTo("\"dir with space\\\\\"");
    }

    @Test
    public void backslashBeforeQuoteGetsTwoNPlusOne() {
        // a\"  -> backslash then quote -> \\\" (2*1+1 = 3 backslashes then quote... input is a\" )
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a\\\""))
                .isEqualTo("a\\\\\\\"");
    }

    @Test
    public void emptyArgIsUnchanged() {
        // the character loop never runs, and without whitespace nothing is quoted
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("")).isEqualTo("");
    }

    @Test
    public void trailingBackslashesUnquotedAreNotDoubled() {
        // not quoted, so N trailing backslashes stay N - for any N, not just 1
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("C:\\path\\"))
                .isEqualTo("C:\\path\\");
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a\\\\"))
                .isEqualTo("a\\\\");
    }

    @Test
    public void trailingBackslashWithWhitespaceIsDoubledInsideQuotes() {
        // the shape --DataDir=<path> produces: quoted because of the space, the interior backslash
        // left alone, the trailing one doubled so the closing quote stays an argument delimiter
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("--Foo=C:\\dir with space\\"))
                .isEqualTo("\"--Foo=C:\\dir with space\\\\\"");
    }

    @Test
    public void alreadyQuotedArgHasBothQuotesEscaped() {
        // "abc" -> \"abc\" - no whitespace, so no new surrounding quotes are added
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("\"abc\""))
                .isEqualTo("\\\"abc\\\"");
    }

    // Only space, tab and newline are whitespace, as in C#'s ContainsWhitespace
    @Test
    public void onlySpaceTabAndNewlineTriggerQuoting() {
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a" + CR + "b")).isEqualTo("a" + CR + "b");
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a" + NBSP + "b")).isEqualTo("a" + NBSP + "b");
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a" + VT + "b")).isEqualTo("a" + VT + "b");
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a" + FF + "b")).isEqualTo("a" + FF + "b");

        // the three that do count, as the contrast
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a b")).isEqualTo("\"a b\"");
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a\tb")).isEqualTo("\"a\tb\"");
        assertThat(CommandLineArgumentEscaper.escapeSingleArg("a\nb")).isEqualTo("\"a\nb\"");
    }

    @Test
    public void escapeAndConcatenateJoinsWithSpaces() {
        assertThat(CommandLineArgumentEscaper.escapeAndConcatenate(
                Arrays.asList("--A=1", "C:\\Program Files\\x", "--B=2")))
                .isEqualTo("--A=1 \"C:\\Program Files\\x\" --B=2");
    }

    @Test
    public void escapeSingleArgRejectsNull() {
        assertThatThrownBy(() -> CommandLineArgumentEscaper.escapeSingleArg(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void escapeAndConcatenateRejectsNullElement() {
        assertThatThrownBy(() -> CommandLineArgumentEscaper.escapeAndConcatenate(
                Arrays.asList("--A=1", null, "--B=2")))
                .isInstanceOf(NullPointerException.class);
    }
}
