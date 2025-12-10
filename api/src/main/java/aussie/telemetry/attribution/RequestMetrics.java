package aussie.telemetry.attribution;

/**
 * Immutable record containing request-level metrics for attribution.
 *
 * @param requestBytes size of the request body in bytes
 * @param responseBytes size of the response body in bytes
 * @param durationMs total request duration in milliseconds
 * @param statusCode HTTP response status code
 * @param success whether the request was successful (2xx/3xx)
 */
public record RequestMetrics(long requestBytes, long responseBytes, long durationMs, int statusCode, boolean success) {

    /**
     * Creates RequestMetrics from component values.
     *
     * @param requestBytes request body size
     * @param responseBytes response body size
     * @param durationMs request duration
     * @param statusCode HTTP status code
     * @return new RequestMetrics instance
     */
    public static RequestMetrics of(long requestBytes, long responseBytes, long durationMs, int statusCode) {
        boolean success = statusCode >= 200 && statusCode < 400;
        return new RequestMetrics(requestBytes, responseBytes, durationMs, statusCode, success);
    }

    /**
     * Returns the total bytes transferred (request + response).
     *
     * @return total bytes
     */
    public long totalBytes() {
        return requestBytes + responseBytes;
    }
}
