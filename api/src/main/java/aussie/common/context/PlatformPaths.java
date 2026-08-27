package aussie.common.context;

import java.util.Locale;
import java.util.Set;

/** Platform-owned path prefixes that service routing must not claim. */
public final class PlatformPaths {

    private static final Set<String> ROOTS = Set.of("admin", "auth", "q");

    private PlatformPaths() {}

    public static boolean owns(String path) {
        if (path == null) {
            return false;
        }
        final var start = path.startsWith("/") ? 1 : 0;
        final var slash = path.indexOf('/', start);
        final var root = slash < 0 ? path.substring(start) : path.substring(start, slash);
        return ROOTS.contains(root.toLowerCase(Locale.ROOT));
    }
}
