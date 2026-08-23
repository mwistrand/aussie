package aussie.core.service.auth;

import java.util.Collection;
import java.util.Map;

/** Size and type boundary for claims copied into gateway-issued tokens. */
public final class IssuedClaimPolicy {

    private static final int MAX_DEPTH = 4;
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_STRING_LENGTH = 4096;
    private static final int MAX_CONTAINER_SIZE = 64;

    private IssuedClaimPolicy() {}

    public static boolean isAllowed(String name, Object value) {
        return isAllowedName(name) && isAllowed(value, 0);
    }

    private static boolean isAllowed(Object value, int depth) {
        if (value == null || value instanceof Boolean) {
            return true;
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            return false;
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            return false;
        }
        if (value instanceof Number number) {
            return number.toString().length() <= MAX_STRING_LENGTH;
        }
        if (value instanceof CharSequence text) {
            return text.length() <= MAX_STRING_LENGTH;
        }
        if (depth >= MAX_DEPTH) {
            return false;
        }
        if (value instanceof Collection<?> collection) {
            return collection.size() <= MAX_CONTAINER_SIZE
                    && collection.stream().allMatch(item -> isAllowed(item, depth + 1));
        }
        if (value instanceof Map<?, ?> map) {
            return map.size() <= MAX_CONTAINER_SIZE
                    && map.entrySet().stream()
                            .allMatch(entry -> entry.getKey() instanceof String key
                                    && isAllowedName(key)
                                    && isAllowed(entry.getValue(), depth + 1));
        }
        return false;
    }

    private static boolean isAllowedName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_NAME_LENGTH;
    }
}
