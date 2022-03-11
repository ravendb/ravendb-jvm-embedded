package net.ravendb.embedded;

import java.io.*;

public class ExtractFromJarServerProvider implements IProvideRavenDBServer {

    @Override
    public void provide(String targetDirectory) throws IOException {
        try (InputStream resourceAsStream =
                ExtractFromJarServerProvider.class.getResourceAsStream("/ravendb-server.zip")) {

            if (resourceAsStream == null) {
                throw new IllegalStateException("Unable to find resource: ravendb-server.zip");
            }

            ExtractFromZipServerProvider.unzip(resourceAsStream, targetDirectory);
        }
    }
}
