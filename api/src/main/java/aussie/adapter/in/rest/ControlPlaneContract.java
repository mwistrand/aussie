package aussie.adapter.in.rest;

/** Stable wire-contract identifiers for gateway-owned control-plane APIs. */
public final class ControlPlaneContract {

    public static final String VERSION = "v1";
    public static final String VERSION_HEADER = "X-Aussie-Control-Plane-Version";
    public static final String SPECIFICATION_PATH = "/openapi.yaml";

    private ControlPlaneContract() {}
}
