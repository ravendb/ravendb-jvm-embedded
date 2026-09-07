package net.ravendb.embedded;

/**
 * Test helper: prints each argument it received on its own line, so a test can assert the exact
 * argv a child process sees after {@link ProcessBuilder} construction. Deliberately dependency-free
 * so it can be launched with just {@code target/test-classes} on the classpath.
 */
public class ArgPrinter {

    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            sb.append(arg).append(System.lineSeparator());
        }
        System.out.print(sb);
        System.out.flush();
    }
}
