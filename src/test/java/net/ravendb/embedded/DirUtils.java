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
}
