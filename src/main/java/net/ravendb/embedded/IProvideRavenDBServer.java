package net.ravendb.embedded;

import java.io.IOException;

public interface IProvideRavenDBServer {
    void provide(String targetDirectory) throws IOException;
}
