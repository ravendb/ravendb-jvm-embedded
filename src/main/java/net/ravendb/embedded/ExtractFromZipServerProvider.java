package net.ravendb.embedded;

import org.apache.commons.io.IOUtils;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ExtractFromZipServerProvider implements IProvideRavenDBServer {

    private final String sourceLocation;

    public ExtractFromZipServerProvider(String sourceLocation) {
        this.sourceLocation = sourceLocation;
    }

    @Override
    public void provide(String targetDirectory) throws IOException {
        try (InputStream resourceAsStream = new FileInputStream(sourceLocation)) {
            unzip(resourceAsStream, targetDirectory);
        }
    }

    public static void unzip(InputStream source, String out) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(source)) {

            ZipEntry entry = zis.getNextEntry();

            while (entry != null) {

                File file = new File(out, entry.getName());

                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    File parent = file.getParentFile();

                    if (!parent.exists()) {
                        parent.mkdirs();
                    }

                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
                        IOUtils.copy(zis, bos);
                    }
                }
                entry = zis.getNextEntry();
            }
        }
    }
}
