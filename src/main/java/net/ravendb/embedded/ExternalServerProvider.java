package net.ravendb.embedded;

import java.io.File;
import java.io.IOException;

public class ExternalServerProvider implements IProvideRavenDBServer {

    public static final String SERVER_DLL_FILENAME = "Raven.Server.dll";

    private final String serverLocation;

    private final IProvideRavenDBServer innerProvider;

    public ExternalServerProvider(String serverLocation) {
        this.serverLocation = serverLocation;

        File fileServerLocation = new File(serverLocation);
        if (!fileServerLocation.exists()) {
            throw new IllegalArgumentException("Server location doesn't exist: " + serverLocation);
        }

        // check if target is file - we assume it is zip file
        if (fileServerLocation.isFile()) {
            innerProvider = new ExtractFromZipServerProvider(serverLocation);
            return;
        }

        // alternately it might be directory - look for Raven.Server.exe inside
        if (fileServerLocation.isDirectory() && new File(fileServerLocation, SERVER_DLL_FILENAME).exists()) {
            innerProvider = new CopyServerProvider(serverLocation);
            return;
        }

        throw new IllegalArgumentException("Unable to find RavenDB server (expected directory with "
                + SERVER_DLL_FILENAME
                + ") or zip file. Used directory = "
                + serverLocation);
    }

    @Override
    public void provide(String targetDirectory) throws IOException {
        innerProvider.provide(targetDirectory);
    }
}
