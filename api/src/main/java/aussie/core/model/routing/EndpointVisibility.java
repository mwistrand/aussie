package aussie.core.model.routing;

public enum EndpointVisibility {
    /**
     * Endpoint is accessible by anyone.
     */
    PUBLIC,

    /**
     * Endpoint is accessible only by configured internal sources.
     */
    PRIVATE
}
