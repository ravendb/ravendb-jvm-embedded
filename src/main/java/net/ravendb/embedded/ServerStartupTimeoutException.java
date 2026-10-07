package net.ravendb.embedded;

import net.ravendb.client.exceptions.RavenException;

/**
 * Thrown when the embedded RavenDB server does not become available within
 * {@link ServerOptions#getMaxServerStartupTimeDuration()}. Distinct from the generic
 * {@link IllegalStateException} used for other startup failures.
 */
public class ServerStartupTimeoutException extends RavenException {
    public ServerStartupTimeoutException(String message) {
        super(message);
    }
}
