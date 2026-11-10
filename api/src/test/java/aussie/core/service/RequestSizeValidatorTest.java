package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.LimitsConfig;
import aussie.core.model.common.ValidationResult;
import aussie.core.service.common.RequestSizeValidator;

@DisplayName("RequestSizeValidator")
class RequestSizeValidatorTest {

    private RequestSizeValidator validator;
    private TestLimitsConfig config;

    @BeforeEach
    void setUp() {
        config = new TestLimitsConfig();
        validator = new RequestSizeValidator(config);
    }

    @Nested
    @DisplayName("Individual Header Size Validation")
    class HeaderSizeTests {

        @Test
        @DisplayName("Should accept header within limit")
        void shouldAcceptHeaderWithinLimit() {
            config.setMaxHeaderSize(4096);
            var result = validator.validateHeaderSize("X-Custom-Header", "short-value");

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should reject oversized header")
        void shouldRejectOversizedHeader() {
            config.setMaxHeaderSize(100);
            var largeValue = "x".repeat(200);
            var result = validator.validateHeaderSize("X-Large-Header", largeValue);

            assertFalse(result.isValid());
            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(431, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("X-Large-Header"));
        }

        @Test
        @DisplayName("Should accept null header value")
        void shouldAcceptNullHeaderValue() {
            config.setMaxHeaderSize(100);
            var result = validator.validateHeaderSize("X-Null-Header", null);

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should include header name in size calculation")
        void shouldIncludeHeaderNameInSizeCalculation() {
            // Header format: "Name: Value" in bytes
            config.setMaxHeaderSize(20);
            // "X-Test: abc" = 11 bytes, should pass
            assertTrue(validator.validateHeaderSize("X-Test", "abc").isValid());

            // "X-Test: abcdefghijklmno" = 25 bytes, should fail
            assertFalse(
                    validator.validateHeaderSize("X-Test", "abcdefghijklmno").isValid());
        }

        @Test
        @DisplayName("Should reject the first oversized header in an iterable")
        void shouldRejectFirstOversizedHeaderInIterable() {
            config.setMaxHeaderSize(20);

            var result = validator.validateHeaders(List.of(
                    Map.entry("X-Small", "ok"), Map.entry("X-Large", "x".repeat(30)), Map.entry("X-Later", "ok")));

            assertFalse(result.isValid());
            assertTrue(((ValidationResult.Invalid) result).reason().contains("X-Large"));
        }
    }

    /**
     * Simple test implementation of LimitsConfig.
     */
    private static class TestLimitsConfig implements LimitsConfig {
        private int maxHeaderSize = 8 * 1024; // 8KB

        void setMaxHeaderSize(int size) {
            this.maxHeaderSize = size;
        }

        @Override
        public int maxHeaderSize() {
            return maxHeaderSize;
        }

        @Override
        public long maxBodySize() {
            return 10 * 1024 * 1024;
        }

        @Override
        public long maxResponseBodySize() {
            return 100 * 1024 * 1024;
        }

        @Override
        public int maxTotalHeadersSize() {
            return 32 * 1024;
        }

        @Override
        public int maxInitialLineLength() {
            return 4096;
        }

        @Override
        public int maxFormAttributeSize() {
            return 8192;
        }

        @Override
        public int maxFormBufferedBytes() {
            return 1024;
        }

        @Override
        public int maxFormFields() {
            return 256;
        }

        @Override
        public int maxParameters() {
            return 1000;
        }
    }
}
