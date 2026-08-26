package aussie.common.context;

/** Names for request attributes shared by inbound adapters and system filters. */
public final class RouteContextAttributes {

    /** Optional route lookup result cached for downstream consumers. */
    public static final String LOOKUP = "aussie.route.lookup";

    /** {@code Boolean.TRUE} when the resolved route is public; absent otherwise. */
    public static final String PUBLIC = "aussie.route.public";

    private RouteContextAttributes() {}
}
