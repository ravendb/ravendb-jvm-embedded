package net.ravendb.embedded;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class CopyServerProvider implements IProvideRavenDBServer {

    public static final String SERVER_FILES = "target/nuget/contentFiles/any/any/RavenDBServer";

    @Override
    public void provide(String targetDirectory) throws IOException {
        if (!new File("target").exists()) {
            throw new IllegalStateException("Unable to find 'target' directory in current working dir ("
                    + new File(".").getAbsolutePath() + "). Please make sure you execute test in root project directory " +
                    ". The one with pom.xml file. ");
        }

        FileUtils.copyDirectory(new File(SERVER_FILES), new File(targetDirectory));
    }
}
