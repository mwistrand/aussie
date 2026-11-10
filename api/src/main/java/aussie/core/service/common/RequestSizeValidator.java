package aussie.core.service.common;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.config.LimitsConfig;
import aussie.core.model.common.ValidationResult;

/**
 * Validate HTTP request sizes against configured limits.
 *
 * <p>Enforces the application-level size constraint that the native HTTP server cannot express:
 * <ul>
 *   <li>Individual header size</li>
 * </ul>
 *
 * <p>Returns validation results that map to HTTP 431 (Request Header Fields Too Large).
 */
@ApplicationScoped
public class RequestSizeValidator {

    private final LimitsConfig config;

    @Inject
    public RequestSizeValidator(LimitsConfig config) {
        this.config = config;
    }

    /**
     * Validate individual header size against configured limit.
     *
     * @param headerName  the header name
     * @param headerValue the header value
     * @return validation result (header too large if exceeded)
     */
    public ValidationResult validateHeaderSize(String headerName, String headerValue) {
        if (headerValue == null) {
            return ValidationResult.valid();
        }

        var maxSize = config.maxHeaderSize();
        var headerSize = (headerName + ": " + headerValue).getBytes(StandardCharsets.UTF_8).length;

        if (headerSize > maxSize) {
            return ValidationResult.headerTooLarge(String.format(
                    "Header '%s' size %d exceeds maximum allowed size %d", headerName, headerSize, maxSize));
        }
        return ValidationResult.valid();
    }

    /**
     * Validate each header in transport order and return the first failure.
     *
     * @param headers header entries from the transport layer
     * @return the first validation failure, or valid if all headers fit
     */
    public ValidationResult validateHeaders(Iterable<Map.Entry<String, String>> headers) {
        for (final var header : headers) {
            final var result = validateHeaderSize(header.getKey(), header.getValue());
            if (result.isInvalid()) {
                return result;
            }
        }
        return ValidationResult.valid();
    }
}
