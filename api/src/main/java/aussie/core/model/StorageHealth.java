package aussie.core.model;

/**
 * Health status for storage backends.
 */
public record StorageHealth(boolean healthy, String name, String message, long latencyMs) {

    public static StorageHealth healthy(String name, long latencyMs) {
        return new StorageHealth(true, name, "OK", latencyMs);
    }

    public static StorageHealth unhealthy(String name, String message) {
        return new StorageHealth(false, name, message, -1);
    }
}
