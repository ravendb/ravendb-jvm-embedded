package net.ravendb.embedded;

import com.google.common.io.Files;
import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.conventions.DocumentConventions;
import net.ravendb.client.documents.session.IDocumentSession;
import net.ravendb.client.primitives.CleanCloseable;
import net.ravendb.client.primitives.Reference;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

public class BasicTest {

    @Test
    public void testEmbedded() {
        Reference<String> tempDir = new Reference<>();
        try (CleanCloseable context = withTemporaryDir(tempDir)) {
            try (EmbeddedServer embedded = new EmbeddedServer()) {
                ServerOptions serverOptions = new ServerOptions();
                serverOptions.setTargetServerLocation(Paths.get(tempDir.value, "RavenDBServer").toString());
                serverOptions.setDataDirectory(Paths.get(tempDir.value, "RavenDB").toString());
                serverOptions.setLogsPath(Paths.get(tempDir.value, "Logs").toString());
                serverOptions.provider = new CopyServerProvider();
                embedded.startServer(serverOptions);

                DatabaseOptions databaseOptions = new DatabaseOptions("Test");
                DocumentConventions conventions = new DocumentConventions();
                conventions.setSaveEnumsAsIntegers(true);
                databaseOptions.setConventions(conventions);

                try (IDocumentStore store = embedded.getDocumentStore(databaseOptions)) {
                    assertThat(store.getConventions().isSaveEnumsAsIntegers())
                            .isTrue();
                    assertThat(store.getRequestExecutor().getConventions().isSaveEnumsAsIntegers())
                            .isTrue();

                    try (IDocumentSession session = store.openSession()) {
                        Person person = new Person();
                        person.setName("John");

                        session.store(person, "people/1");
                        session.saveChanges();
                    }
                }
            }

            try (EmbeddedServer embedded = new EmbeddedServer()) {
                ServerOptions serverOptions = new ServerOptions();
                serverOptions.setTargetServerLocation(Paths.get(tempDir.value, "RavenDBServer").toString());
                serverOptions.setDataDirectory(Paths.get(tempDir.value, "RavenDB").toString());
                serverOptions.provider = new CopyServerProvider();
                embedded.startServer(serverOptions);

                try (IDocumentStore store = embedded.getDocumentStore("Test")) {
                    assertThat(store.getConventions().isSaveEnumsAsIntegers())
                            .isFalse();
                    assertThat(store.getRequestExecutor().getConventions().isSaveEnumsAsIntegers())
                            .isFalse();

                    try (IDocumentSession session = store.openSession()) {
                        Person person = session.load(Person.class, "people/1");

                        assertThat(person)
                                .isNotNull();

                        assertThat(person.getName())
                                .isEqualTo("John");
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
