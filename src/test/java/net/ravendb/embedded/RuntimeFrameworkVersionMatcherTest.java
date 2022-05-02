package net.ravendb.embedded;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RuntimeFrameworkVersionMatcherTest {

    @Test
    public void matchTest1() {
        ServerOptions options = new ServerOptions();

        String defaultFrameworkVersion = ServerOptions.INSTANCE.getFrameworkVersion();

        assertThat(defaultFrameworkVersion)
                .endsWith(String.valueOf(RuntimeFrameworkVersionMatcher.GREATER_OR_EQUAL));

        options.setFrameworkVersion(null);
        assertThat(RuntimeFrameworkVersionMatcher.match(options))
                .isNull();

        options = new ServerOptions();

        RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion frameworkVersion = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion(options.getFrameworkVersion());
        frameworkVersion.setPatch(null);

        options.setFrameworkVersion(frameworkVersion.toString());
        String match = RuntimeFrameworkVersionMatcher.match(options);
        assertThat(match)
                .isNotNull();

        RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion matchFrameworkVersion = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion(match);
        assertThat(matchFrameworkVersion.getMajor())
                .isNotNull();
        assertThat(matchFrameworkVersion.getMinor())
                .isNotNull();
        assertThat(matchFrameworkVersion.getPatch())
                .isNotNull();

        assertThat(frameworkVersion.match(matchFrameworkVersion))
                .isTrue();
    }

    @Test
    public void matchTest2() {
        List<RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion> runtimes = getRuntimes();

        RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.1.1");
        assertThat(RuntimeFrameworkVersionMatcher.match(runtime, runtimes))
                .isEqualTo("3.1.1");

        runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("2.1.11");
        assertThat(RuntimeFrameworkVersionMatcher.match(runtime, runtimes))
                .isEqualTo("2.1.11");

        runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.1.x");
        assertThat(RuntimeFrameworkVersionMatcher.match(runtime, runtimes))
                .isEqualTo("3.1.3");

        runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.x");
        assertThat(RuntimeFrameworkVersionMatcher.match(runtime, runtimes))
                .isEqualTo("3.2.3");

        runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.x.x");
        assertThat(RuntimeFrameworkVersionMatcher.match(runtime, runtimes))
                .isEqualTo("3.2.3");

        runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("5.0.x");
        assertThat(RuntimeFrameworkVersionMatcher.match(runtime, runtimes))
                .isEqualTo("5.0.4");

        runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("x");
        assertThat(RuntimeFrameworkVersionMatcher.match(runtime, runtimes))
                .isEqualTo("5.0.4");

        runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("5.0.x-rc.2.20475.17");
        assertThat(RuntimeFrameworkVersionMatcher.match(runtime, runtimes))
                .isEqualTo("5.0.0-rc.2.20475.17");

        runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("6.x");

        RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion finalRuntime = runtime;
        assertThatThrownBy(() -> RuntimeFrameworkVersionMatcher.match(finalRuntime, runtimes))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void matchTest3() throws Exception {
        RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.1.0-rc");
        assertThat(runtime.toString())
                .isEqualTo("3.1.0-rc");

        runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("5.0.0-rc.2.20475.17");
        assertThat(runtime.toString())
                .isEqualTo("5.0.0-rc.2.20475.17");
    }

    @Test
    public void matchTest4() {
        List<RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion> runtimes = getRuntimes();

        RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.1.1+");

        assertThat(runtime.toString())
                .isEqualTo("3.1.1+");
        assertThat(RuntimeFrameworkVersionMatcher.match(runtime, runtimes))
                .isEqualTo("3.1.3");

        runtime = new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.1.4+");
        assertThat(runtime.toString())
                .isEqualTo("3.1.4+");
        RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion finalRuntime = runtime;
        assertThatThrownBy(() -> RuntimeFrameworkVersionMatcher.match(finalRuntime, runtimes))
                .hasMessageContaining("Could not find a matching runtime for '3.1.4+'. Available runtimes:");

        assertThatThrownBy(() -> new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("6.0.0+-preview.6.21352.12"))
                .hasMessageContaining("Cannot set 'patch' with value '0+' because '+' is not allowed when suffix ('preview.6.21352.12') is set");

        assertThatThrownBy(() -> new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("6+"))
                .hasMessageContaining("Cannot set 'major' with value '6+' because '+' is not allowed.");

        assertThatThrownBy(() -> new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.1+"))
                .hasMessageContaining("Cannot set 'minor' with value '1+' because '+' is not allowed.");
    }

    private static List<RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion> getRuntimes() {
        List<RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion> result = new ArrayList<>();
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("2.1.3"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("2.1.4"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("2.1.11"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("2.2.0"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("2.2.1"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.1.0"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.1.1"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.1.2"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.1.3"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("3.2.3"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("5.0.0-rc.2.20475.17"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("5.0.3"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("5.0.4"));
        result.add(new RuntimeFrameworkVersionMatcher.RuntimeFrameworkVersion("6.0.0-preview.6.21352.12"));

        return result;
    }
}
