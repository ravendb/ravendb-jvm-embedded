package net.ravendb.embedded;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class CommandLineArgumentEscaperTest {

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
    public void escapeAndConcatenateJoinsWithSpaces() {
        assertThat(CommandLineArgumentEscaper.escapeAndConcatenate(
                Arrays.asList("--A=1", "C:\\Program Files\\x", "--B=2")))
                .isEqualTo("--A=1 \"C:\\Program Files\\x\" --B=2");
    }

    @Test
    public void nullReturnsNull() {
        assertThat(CommandLineArgumentEscaper.escapeSingleArg(null)).isNull();
    }
}
