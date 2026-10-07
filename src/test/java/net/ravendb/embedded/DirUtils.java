package net.ravendb.embedded;

import com.google.common.io.Files;
import net.ravendb.client.primitives.CleanCloseable;
import net.ravendb.client.primitives.Reference;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class DirUtils {
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

    /**
     * Deletes whatever a run left at {@link ServerOptions}' default locations, which are all relative
     * to the working directory - the maven module, i.e. the repository - rather than to a temp dir:
     * {@code RavenDB} (data, with {@code RavenDB/Logs} inside it) and {@code RavenDBServer} (the
     * extracted server). Read from a default-constructed {@code ServerOptions} so this keeps matching
     * the library instead of hardcoding the names.
     */
    public static void deleteDefaultServerArtifacts() {
        ServerOptions defaults = new ServerOptions();

        deleteDirectory(defaults.getLogsPath());
        deleteDirectory(defaults.getDataDirectory());
        deleteDirectory(ServerOptions.DEFAULT_SERVER_LOCATION);
    }

    private static void deleteDirectory(String path) {
        File directory = new File(path);
        if (!directory.isDirectory()) {
            return;
        }

        try {
            FileUtils.deleteDirectory(directory);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
