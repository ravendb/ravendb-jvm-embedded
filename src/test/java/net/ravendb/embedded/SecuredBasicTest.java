package net.ravendb.embedded;

import com.google.common.io.Files;
import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.conventions.DocumentConventions;
import net.ravendb.client.documents.session.IDocumentSession;
import net.ravendb.client.primitives.CleanCloseable;
import net.ravendb.client.primitives.Reference;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

public class SecuredBasicTest {

    public static final String SERVER_CERTIFICATE_LOCATION = "C:\\temp\\java_certs\\server.pfx";
    public static final String CA_CERTIFICATE_LOCATION = "C:\\temp\\java_certs\\ca.crt";

    @Test
    @Disabled
    public void testSecuredEmbedded() {
        Reference<String> tempDir = new Reference<>();
        try (CleanCloseable context = withTemporaryDir(tempDir)) {
            try (EmbeddedServer embedded = new EmbeddedServer()) {
                ServerOptions serverOptions = new ServerOptions();

                serverOptions.setServerUrl("https://a.javatest11.development.run:7654");
                serverOptions.secured(SERVER_CERTIFICATE_LOCATION, CA_CERTIFICATE_LOCATION);

                serverOptions.setTargetServerLocation(Paths.get(tempDir.value, "RavenDBServer").toString());
                serverOptions.setDataDirectory(Paths.get(tempDir.value, "RavenDB").toString());
                serverOptions.setLogsPath(Paths.get(tempDir.value, "Logs").toString());
                serverOptions.provider = new CopyServerProvider();
                embedded.startServer(serverOptions);

                DatabaseOptions databaseOptions = new DatabaseOptions("Test");

                try (IDocumentStore store = embedded.getDocumentStore(databaseOptions)) {
                    try (IDocumentSession session = store.openSession()) {
                        Person person = new Person();
                        person.setName("Marcin");

                        session.store(person, "people/1");
                        session.saveChanges();
                    }
                }
            }
        }
    }

    public static CleanCloseable withTemporaryDir(Reference<String> tempDirRef) {
        File tempDir = Files.createTempDir();

        tempDirRef.value = tempDir.getAbsolutePath();

        return () -> {
            try {
                FileUtils.deleteDirectory(tempDir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        };
    }

    @SuppressWarnings("unused")
    public static class Person {
        private String id;
        private String name;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
