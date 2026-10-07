package net.ravendb.embedded;

import net.ravendb.client.primitives.CleanCloseable;
import net.ravendb.client.primitives.Reference;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves that a license JSON set through {@link LicensingOptions#setLicense(String)} survives all the
 * way into the server's own command-line parser. The value is deliberately shaped like a real license
 * - double quotes plus a name containing a space - because that is what used to break: Windows handed
 * the argument over with its quotes eaten and split at the space, and the server refused to start with
 * "Unrecognized command or argument".
 * <p>
 * The license itself is bogus. That is fine and is the point: with the default
 * {@code throwOnInvalidOrMissingLicense = false} an unusable license is merely rejected internally,
 * so a server that reaches "Server available on:" proves the argument was <i>parsed</i>, which is the
 * part that was broken. Requires the server payload under {@code target/nuget/...}
 * (via {@code mvn generate-resources}); skipped - not silently disabled - when it is missing.
 */
public class LicensingTest {

    private static final String BOGUS_LICENSE =
            "{\"Id\":\"a1b2c3d4-0000-0000-0000-000000000000\",\"Name\":\"Bogus Corp Ltd\",\"Keys\":[\"AAAABBBBCCCC\"]}";

    @Test
    public void serverStartsWithLicenseJsonContainingQuotesAndSpaces() throws Exception {
        assumeTrue(new File(CopyServerFromNugetProvider.SERVER_FILES).isDirectory(),
                "RavenDB server payload missing - run `mvn generate-resources`");

        Reference<String> tempDir = new Reference<>();
        try (CleanCloseable context = DirUtils.withTemporaryDir(tempDir)) {
            try (EmbeddedServer embedded = new EmbeddedServer()) {
                ServerOptions options = new ServerOptions();
                options.setTargetServerLocation(Paths.get(tempDir.value, "RavenDBServer").toString());
                options.setDataDirectory(Paths.get(tempDir.value, "RavenDB").toString());
                options.setLogsPath(Paths.get(tempDir.value, "Logs").toString());
                options.provider = new CopyServerFromNugetProvider();
                options.getLicensing().setLicense(BOGUS_LICENSE);

                embedded.startServer(options);

                assertThat(embedded.getServerUri()).startsWith("http");
            }
        }
    }
}
