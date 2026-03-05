package aussie.core.service.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.SessionConfig;

@DisplayName("SessionFeatureFlag")
@ExtendWith(MockitoExtension.class)
class SessionFeatureFlagTest {

    @Mock
    private SessionConfig config;

    @Mock
    private SessionConfig.JwsConfig jwsConfig;

    private SessionFeatureFlag featureFlag;

    @BeforeEach
    void setUp() {
        featureFlag = new SessionFeatureFlag(config);
    }

    @Nested
    @DisplayName("isSessionsEnabled")
    class IsSessionsEnabled {

        @Test
        @DisplayName("should return true when sessions are enabled")
        void enabled() {
            when(config.enabled()).thenReturn(true);

            assertTrue(featureFlag.isSessionsEnabled());
        }

        @Test
        @DisplayName("should return false when sessions are disabled")
        void disabled() {
            when(config.enabled()).thenReturn(false);

            assertFalse(featureFlag.isSessionsEnabled());
        }
    }

    @Nested
    @DisplayName("isJwsEnabled")
    class IsJwsEnabled {

        @Test
        @DisplayName("should return true when both sessions and JWS are enabled")
        void bothEnabled() {
            when(config.enabled()).thenReturn(true);
            when(config.jws()).thenReturn(jwsConfig);
            when(jwsConfig.enabled()).thenReturn(true);

            assertTrue(featureFlag.isJwsEnabled());
        }

        @Test
        @DisplayName("should return false when sessions enabled but JWS disabled")
        void sessionsEnabledJwsDisabled() {
            when(config.enabled()).thenReturn(true);
            when(config.jws()).thenReturn(jwsConfig);
            when(jwsConfig.enabled()).thenReturn(false);

            assertFalse(featureFlag.isJwsEnabled());
        }

        @Test
        @DisplayName("should return false when sessions disabled")
        void sessionsDisabled() {
            when(config.enabled()).thenReturn(false);

            assertFalse(featureFlag.isJwsEnabled());
        }
    }

    @Nested
    @DisplayName("isSlidingExpirationEnabled")
    class IsSlidingExpirationEnabled {

        @Test
        @DisplayName("should return true when sessions enabled and sliding expiration enabled")
        void bothEnabled() {
            when(config.enabled()).thenReturn(true);
            when(config.slidingExpiration()).thenReturn(true);

            assertTrue(featureFlag.isSlidingExpirationEnabled());
        }

        @Test
        @DisplayName("should return false when sessions enabled but sliding expiration disabled")
        void slidingDisabled() {
            when(config.enabled()).thenReturn(true);
            when(config.slidingExpiration()).thenReturn(false);

            assertFalse(featureFlag.isSlidingExpirationEnabled());
        }

        @Test
        @DisplayName("should return false when sessions disabled")
        void sessionsDisabled() {
            when(config.enabled()).thenReturn(false);

            assertFalse(featureFlag.isSlidingExpirationEnabled());
        }
    }

    @Nested
    @DisplayName("isConflictDetectionEnabled")
    class IsConflictDetectionEnabled {

        @Test
        @DisplayName("should return true when sessions are enabled")
        void enabled() {
            when(config.enabled()).thenReturn(true);

            assertTrue(featureFlag.isConflictDetectionEnabled());
        }

        @Test
        @DisplayName("should return false when sessions are disabled")
        void disabled() {
            when(config.enabled()).thenReturn(false);

            assertFalse(featureFlag.isConflictDetectionEnabled());
        }
    }
}
