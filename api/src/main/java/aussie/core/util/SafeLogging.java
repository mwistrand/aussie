package aussie.core.util;

/** Helpers for keeping security-sensitive values out of logs. */
public final class SafeLogging {

    private SafeLogging() {}

    /** Return a stable short identifier without exposing the supplied value. */
    public static String identifier(String value) {
        return value == null || value.isBlank() ? "absent" : SecureHash.truncatedSha256(value, 12);
    }

    /** Return a stable lockout pseudonym while preserving its bounded key type. */
    public static String lockoutKey(String key) {
        if (key == null) {
            return "unknown";
        }
        final var separator = key.indexOf(':');
        if (separator <= 0 || separator == key.length() - 1) {
            return "unknown:" + identifier(key);
        }
        final var type =
                switch (key.substring(0, separator)) {
                    case "ip", "user", "apikey" -> key.substring(0, separator);
                    default -> "unknown";
                };
        return type + ":" + identifier(key.substring(separator + 1));
    }

    /** Return only an exception type; exception messages may contain credentials or response bodies. */
    public static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
