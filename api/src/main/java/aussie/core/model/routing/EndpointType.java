package aussie.core.model.routing;

/**
 * Defines the type of endpoint for routing purposes.
 */
public enum EndpointType {
    /**
     * Standard HTTP REST endpoints (default).
     */
    HTTP,

    /**
     * WebSocket endpoints requiring upgrade handling.
     */
    WEBSOCKET
}
