package aussie.core.service.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.common.LimitsConfig;
import aussie.core.model.common.ValidationResult;

/**
 * Validate HTTP request sizes against configured limits.
 *
 * <p>Enforces platform-wide size constraints on:
 * <ul>
 *   <li>Request body size</li>
 *   <li>Individual header size</li>
 *   <li>Total headers size</li>
 * </ul>
 *
 * <p>Returns appropriate validation results that can be mapped to
 * HTTP 413 (Payload Too Large) or 431 (Request Header Fields Too Large) responses.
 */
@ApplicationScoped
public class RequestSizeValidator {

    private final LimitsConfig config;

    @Inject
    public RequestSizeValidator(LimitsConfig config) {
        this.config = config;
    }

    /**
     * Validate request body size against configured limit.
     *
     * @param contentLength the Content-Length header value
     * @return validation result (payload too large if exceeded)
     */
    public ValidationResult validateBodySize(long contentLength) {
        var maxSize = config.maxBodySize();
        if (contentLength > maxSize) {
            return ValidationResult.payloadTooLarge(
                    String.format("Request body size %d exceeds maximum allowed size %d", contentLength, maxSize));
        }
        return ValidationResult.valid();
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
     * Validate total size of all headers against configured limit.
     *
     * @param headers map of header names to values
     * @return validation result (header too large if exceeded)
     */
    public ValidationResult validateTotalHeadersSize(Map<String, List<String>> headers) {
        var maxSize = config.maxTotalHeadersSize();
        var totalSize = 0;

        for (var entry : headers.entrySet()) {
            var headerName = entry.getKey();
            for (var value : entry.getValue()) {
                totalSize += (headerName + ": " + value + "\r\n").getBytes(StandardCharsets.UTF_8).length;
            }
        }

        if (totalSize > maxSize) {
            return ValidationResult.headerTooLarge(
                    String.format("Total headers size %d exceeds maximum allowed size %d", totalSize, maxSize));
        }
        return ValidationResult.valid();
    }

    /**
     * Validate complete request (body and all headers).
     *
     * @param contentLength the Content-Length header value
     * @param headers       map of header names to values
     * @return first validation failure, or valid if all checks pass
     */
    public ValidationResult validateRequest(long contentLength, Map<String, List<String>> headers) {
        var bodyResult = validateBodySize(contentLength);
        if (bodyResult.isInvalid()) {
            return bodyResult;
        }

        for (var entry : headers.entrySet()) {
            for (var value : entry.getValue()) {
                var headerResult = validateHeaderSize(entry.getKey(), value);
                if (headerResult.isInvalid()) {
                    return headerResult;
                }
            }
        }

        return validateTotalHeadersSize(headers);
    }
}
