package aussie.adapter.out.http;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.LimitsConfig;
import aussie.core.config.TrustedProxyConfig;
import aussie.core.model.auth.AccessControlConfig;

@DisplayName("ConfigProducer")
@ExtendWith(MockitoExtension.class)
class ConfigProducerTest {

    private GatewayConfig gatewayConfig;
    private ConfigProducer producer;

    @BeforeEach
    void setUp() {
        gatewayConfig = mock(GatewayConfig.class);
        producer = new ConfigProducer(gatewayConfig);
    }

    @Nested
    @DisplayName("limitsConfig")
    class LimitsConfigProducer {

        @Test
        @DisplayName("returns the limits sub-config from GatewayConfig")
        void returnsLimitsConfig() {
            var limitsConfig = mock(LimitsConfig.class);
            when(gatewayConfig.limits()).thenReturn(limitsConfig);

            assertSame(limitsConfig, producer.limitsConfig());
        }
    }

    @Nested
    @DisplayName("accessControlConfig")
    class AccessControlConfigProducer {

        @Test
        @DisplayName("returns the accessControl sub-config from GatewayConfig")
        void returnsAccessControlConfig() {
            var accessControlConfig = mock(AccessControlConfig.class);
            when(gatewayConfig.accessControl()).thenReturn(accessControlConfig);

            assertSame(accessControlConfig, producer.accessControlConfig());
        }
    }

    @Nested
    @DisplayName("gatewaySecurityConfig")
    class GatewaySecurityConfigProducer {

        @Test
        @DisplayName("returns the security sub-config from GatewayConfig")
        void returnsSecurityConfig() {
            var securityConfig = mock(SecurityConfig.class);
            when(gatewayConfig.security()).thenReturn(securityConfig);

            var result = producer.gatewaySecurityConfig();
            assertSame(securityConfig, result);
        }
    }

    @Nested
    @DisplayName("trustedProxyConfig")
    class TrustedProxyConfigProducer {

        @Test
        @DisplayName("returns the trustedProxy sub-config from GatewayConfig")
        void returnsTrustedProxyConfig() {
            var trustedProxyConfig = mock(TrustedProxyConfig.class);
            when(gatewayConfig.trustedProxy()).thenReturn(trustedProxyConfig);

            assertSame(trustedProxyConfig, producer.trustedProxyConfig());
        }
    }
}
