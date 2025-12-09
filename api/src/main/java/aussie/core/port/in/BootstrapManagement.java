package aussie.core.port.in;

import io.smallrye.mutiny.Uni;

import aussie.core.model.BootstrapResult;

/**
 * Port for bootstrap admin key management.
 *
 * <p>Handles the creation of initial admin API keys for first-time setup
 * or recovery scenarios. The bootstrap key must be provided by the administrator
 * via configuration; it is never auto-generated.
 */
public interface BootstrapManagement {

    /**
     * Create a bootstrap admin key using the configured key.
     *
     * <p>The operation will:
     * <ul>
     *   <li>Validate that a bootstrap key is configured</li>
     *   <li>Validate the key meets minimum length requirements (32 chars)</li>
     *   <li>Create a time-limited admin key with full permissions</li>
     * </ul>
     *
     * @return Uni with the bootstrap result containing the key ID and expiration
     * @throws BootstrapException if bootstrap is misconfigured or fails
     */
    Uni<BootstrapResult> bootstrap();

    /**
     * Check if any admin keys exist in the system.
     *
     * <p>Admin keys are those with either '*' permission or 'admin:write' permission.
     * Revoked and expired keys are not counted.
     *
     * @return Uni with true if at least one valid admin key exists
     */
    Uni<Boolean> hasAdminKeys();

    /**
     * Check if bootstrap should run based on current state and configuration.
     *
     * <p>Bootstrap should run when:
     * <ul>
     *   <li>Bootstrap is enabled AND no admin keys exist, OR</li>
     *   <li>Bootstrap is enabled AND recovery mode is enabled</li>
     * </ul>
     *
     * @return Uni with true if bootstrap should be attempted
     */
    Uni<Boolean> shouldBootstrap();

    /**
     * Exception thrown when bootstrap fails due to misconfiguration or error.
     */
    class BootstrapException extends RuntimeException {
        public BootstrapException(String message) {
            super(message);
        }

        public BootstrapException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
