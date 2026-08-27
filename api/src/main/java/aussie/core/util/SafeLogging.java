package aussie.core.util;

/** Helpers for keeping security-sensitive values out of logs. */
public final class SafeLogging {

    private SafeLogging() {}

    /** Return a stable short identifier without exposing the supplied value. */
    public static String identifier(String value) {
        return value == null || value.isBlank() ? "absent" : SecureHash.truncatedSha256(value, 12);
    }

    /** Return only an exception type; exception messages may contain credentials or response bodies. */
    public static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
