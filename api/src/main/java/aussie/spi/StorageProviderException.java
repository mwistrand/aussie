package aussie.spi;

/**
 * Exception thrown when a storage provider fails to initialize.
 */
public class StorageProviderException extends RuntimeException {

    public StorageProviderException(String message) {
        super(message);
    }

    public StorageProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
