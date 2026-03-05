package aussie.adapter.out.http;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("ForwardedHeaderBuilderFactory")
@ExtendWith(MockitoExtension.class)
class ForwardedHeaderBuilderFactoryTest {

    @Nested
    @DisplayName("getBuilder")
    class GetBuilder {

        @Test
        @DisplayName("returns Rfc7239ForwardedHeaderBuilder when useRfc7239 is true")
        void returnsRfc7239BuilderWhenEnabled() {
            var forwardingConfig = mock(ForwardingConfig.class);
            when(forwardingConfig.useRfc7239()).thenReturn(true);

            var gatewayConfig = mock(GatewayConfig.class);
            when(gatewayConfig.forwarding()).thenReturn(forwardingConfig);

            var rfc7239Builder = mock(Rfc7239ForwardedHeaderBuilder.class);
            var xForwardedBuilder = mock(XForwardedHeaderBuilder.class);

            var factory = new ForwardedHeaderBuilderFactory(gatewayConfig, rfc7239Builder, xForwardedBuilder);

            assertSame(rfc7239Builder, factory.getBuilder());
        }

        @Test
        @DisplayName("returns XForwardedHeaderBuilder when useRfc7239 is false")
        void returnsXForwardedBuilderWhenDisabled() {
            var forwardingConfig = mock(ForwardingConfig.class);
            when(forwardingConfig.useRfc7239()).thenReturn(false);

            var gatewayConfig = mock(GatewayConfig.class);
            when(gatewayConfig.forwarding()).thenReturn(forwardingConfig);

            var rfc7239Builder = mock(Rfc7239ForwardedHeaderBuilder.class);
            var xForwardedBuilder = mock(XForwardedHeaderBuilder.class);

            var factory = new ForwardedHeaderBuilderFactory(gatewayConfig, rfc7239Builder, xForwardedBuilder);

            assertSame(xForwardedBuilder, factory.getBuilder());
        }
    }
}
