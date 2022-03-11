package net.ravendb.embedded;

import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.session.IDocumentSession;
import net.ravendb.client.primitives.CleanCloseable;
import net.ravendb.client.primitives.Reference;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

public class CustomProviderTest {

    @Test
    public void canUseZipAsExternalServerSource() throws Exception {
        Reference<String> tempDir = new Reference<>();
        try (CleanCloseable context = DirUtils.withTemporaryDir(tempDir)) {
            try (EmbeddedServer embedded = new EmbeddedServer()) {
                ServerOptions serverOptions = new ServerOptions();
                configureServerOptions(tempDir, serverOptions);
                serverOptions.withExternalServer("target/ravendb-server.zip");
                embedded.startServer(serverOptions);

                DatabaseOptions databaseOptions = new DatabaseOptions("Test");

                try (IDocumentStore store = embedded.getDocumentStore(databaseOptions)) {
                    try (IDocumentSession session = store.openSession()) {
                        Person loadedPerson = session.load(Person.class, "no-such-person");
                        assertThat(loadedPerson)
                                .isNull();
                    }
                }
            }
        }
    }

    @Test
    public void canUseDirectoryAsExternalServerSource() throws Exception {
        Reference<String> tempDir = new Reference<>();
        try (CleanCloseable context = DirUtils.withTemporaryDir(tempDir)) {
            try (EmbeddedServer embedded = new EmbeddedServer()) {
                ServerOptions serverOptions = new ServerOptions();
                configureServerOptions(tempDir, serverOptions);
                serverOptions.withExternalServer(CopyServerFromNugetProvider.SERVER_FILES);
                embedded.startServer(serverOptions);

                DatabaseOptions databaseOptions = new DatabaseOptions("Test");

                try (IDocumentStore store = embedded.getDocumentStore(databaseOptions)) {
                    try (IDocumentSession session = store.openSession()) {
                        Person loadedPerson = session.load(Person.class, "no-such-person");
                        assertThat(loadedPerson)
                                .isNull();
                    }
                }
            }
        }
    }

    private void configureServerOptions(Reference<String> tempDir, ServerOptions serverOptions) {
        serverOptions.setTargetServerLocation(Paths.get(tempDir.value, "RavenDBServer").toString());
        serverOptions.setDataDirectory(Paths.get(tempDir.value, "RavenDB").toString());
        serverOptions.setLogsPath(Paths.get(tempDir.value, "Logs").toString());

        serverOptions.setCommandLineArgs(Collections.singletonList("--Features.Availability=Experimental"));
    }
}
