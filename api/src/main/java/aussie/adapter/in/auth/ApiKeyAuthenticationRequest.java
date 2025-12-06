package aussie.adapter.in.auth;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;

/**
 * Authentication request containing an API key credential.
 *
 * <p>This is the credential holder passed from {@link ApiKeyAuthenticationMechanism}
 * to {@link ApiKeyIdentityProvider} during Quarkus Security authentication.
 */
public class ApiKeyAuthenticationRequest extends BaseAuthenticationRequest {

    private final String apiKey;

    public ApiKeyAuthenticationRequest(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Returns the plaintext API key to validate.
     *
     * @return the API key
     */
    public String getApiKey() {
        return apiKey;
    }
}
