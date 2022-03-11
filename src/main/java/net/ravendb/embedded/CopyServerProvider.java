package net.ravendb.embedded;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class CopyServerProvider implements IProvideRavenDBServer {

    private final String serverFiles;

    public CopyServerProvider(String serverFiles) {
        this.serverFiles = serverFiles;
    }

    @Override
    public void provide(String targetDirectory) throws IOException {
        FileUtils.copyDirectory(new File(serverFiles), new File(targetDirectory));
    }
}
