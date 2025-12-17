package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.MultivaluedHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.AuthenticationResult;
import aussie.core.model.auth.Permissions;

@DisplayName("NoopAuthProvider")
class NoopAuthProviderTest {

    private static final String CONFIG_KEY = "aussie.auth.dangerous-noop";

    @Nested
    @DisplayName("when dangerous-noop is disabled")
    class WhenDisabled {

        @BeforeEach
        void setUp() {
            System.setProperty(CONFIG_KEY, "false");
        }

        @AfterEach
        void tearDown() {
            System.clearProperty(CONFIG_KEY);
        }

        @Test
        @DisplayName("should not be available")
        void shouldNotBeAvailable() {
            var provider = new NoopAuthProvider();
            assertFalse(provider.isAvailable());
        }

        @Test
        @DisplayName("should skip authentication")
        void shouldSkipAuthentication() {
            var provider = new NoopAuthProvider();
            var headers = new MultivaluedHashMap<String, String>();

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Skip.class, result);
        }
    }

    @Nested
    @DisplayName("when dangerous-noop is enabled")
    class WhenEnabled {

        @BeforeEach
        void setUp() {
            System.setProperty(CONFIG_KEY, "true");
        }

        @AfterEach
        void tearDown() {
            System.clearProperty(CONFIG_KEY);
        }

        @Test
        @DisplayName("should be available")
        void shouldBeAvailable() {
            var provider = new NoopAuthProvider();
            assertTrue(provider.isAvailable());
        }

        @Test
        @DisplayName("should return success with all permissions")
        void shouldReturnSuccessWithAllPermissions() {
            var provider = new NoopAuthProvider();
            var headers = new MultivaluedHashMap<String, String>();

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Success.class, result);
            var success = (AuthenticationResult.Success) result;
            // NoopAuthProvider grants wildcard permission which includes all other
            // permissions
            assertTrue(success.context().hasPermission(Permissions.ALL));
        }

        @Test
        @DisplayName("should authenticate without any headers")
        void shouldAuthenticateWithoutAnyHeaders() {
            var provider = new NoopAuthProvider();
            var headers = new MultivaluedHashMap<String, String>();

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Success.class, result);
        }

        @Test
        @DisplayName("should return system principal")
        void shouldReturnSystemPrincipal() {
            var provider = new NoopAuthProvider();
            var headers = new MultivaluedHashMap<String, String>();

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Success.class, result);
            var success = (AuthenticationResult.Success) result;
            assertEquals("system", success.context().principal().type());
            assertEquals("Development Mode", success.context().principal().name());
        }
    }

    @Nested
    @DisplayName("metadata")
    class MetadataTests {

        @Test
        @DisplayName("should have correct name")
        void shouldHaveCorrectName() {
            var provider = new NoopAuthProvider();
            assertEquals("noop", provider.name());
        }

        @Test
        @DisplayName("should have lowest priority")
        void shouldHaveLowestPriority() {
            var provider = new NoopAuthProvider();
            assertEquals(Integer.MIN_VALUE, provider.priority());
        }
    }
}
