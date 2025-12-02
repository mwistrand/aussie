package aussie.validation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import aussie.config.GatewayConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RequestSizeValidator {

    private final GatewayConfig config;

    @Inject
    public RequestSizeValidator(GatewayConfig config) {
        this.config = config;
    }

    public ValidationResult validateBodySize(long contentLength) {
        var maxSize = config.limits().maxBodySize();
        if (contentLength > maxSize) {
            return ValidationResult.payloadTooLarge(
                String.format("Request body size %d exceeds maximum allowed size %d", contentLength, maxSize)
            );
        }
        return ValidationResult.valid();
    }

    public ValidationResult validateHeaderSize(String headerName, String headerValue) {
        if (headerValue == null) {
            return ValidationResult.valid();
        }

        var maxSize = config.limits().maxHeaderSize();
        var headerSize = (headerName + ": " + headerValue).getBytes(StandardCharsets.UTF_8).length;

        if (headerSize > maxSize) {
            return ValidationResult.headerTooLarge(
                String.format("Header '%s' size %d exceeds maximum allowed size %d", headerName, headerSize, maxSize)
            );
        }
        return ValidationResult.valid();
    }

    public ValidationResult validateTotalHeadersSize(Map<String, List<String>> headers) {
        var maxSize = config.limits().maxTotalHeadersSize();
        var totalSize = 0;

        for (var entry : headers.entrySet()) {
            var headerName = entry.getKey();
            for (var value : entry.getValue()) {
                totalSize += (headerName + ": " + value + "\r\n").getBytes(StandardCharsets.UTF_8).length;
            }
        }

        if (totalSize > maxSize) {
            return ValidationResult.headerTooLarge(
                String.format("Total headers size %d exceeds maximum allowed size %d", totalSize, maxSize)
            );
        }
        return ValidationResult.valid();
    }

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
