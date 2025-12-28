package aussie.adapter.out.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.config.OidcConfig;
import aussie.core.model.auth.OidcTokenExchangeRequest;
import aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod;
import aussie.core.model.auth.OidcTokenExchangeResponse;
import aussie.spi.OidcTokenExchangeProvider;

/**
 * Default OIDC token exchange provider using standard OAuth 2.0 flow (RFC 6749).
 *
 * <p>Supports:
 * <ul>
 *   <li>Authorization code exchange</li>
 *   <li>PKCE code verifier (RFC 7636)</li>
 *   <li>client_secret_basic authentication</li>
 *   <li>client_secret_post authentication</li>
 * </ul>
 */
@ApplicationScoped
public class DefaultOidcTokenExchangeProvider implements OidcTokenExchangeProvider {

    private static final Logger LOG = Logger.getLogger(DefaultOidcTokenExchangeProvider.class);
    private static final String NAME = "default";
    private static final int PRIORITY = 100;
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 3600L;

    private final WebClient webClient;
    private final OidcConfig config;

    @Inject
    public DefaultOidcTokenExchangeProvider(Vertx vertx, OidcConfig config) {
        this.webClient = WebClient.create(vertx);
        this.config = config;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean isAvailable() {
        return config.tokenExchange().tokenEndpoint().isPresent()
                && !config.tokenExchange().tokenEndpoint().get().isBlank();
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        final var tokenEndpoint = config.tokenExchange().tokenEndpoint();
        if (tokenEndpoint.isPresent()) {
            return Optional.of(HealthCheckResponse.named("oidc-token-exchange-default")
                    .up()
                    .withData("provider", NAME)
                    .withData("tokenEndpoint", tokenEndpoint.get())
                    .build());
        } else {
            return Optional.of(HealthCheckResponse.named("oidc-token-exchange-default")
                    .down()
                    .withData("provider", NAME)
                    .withData("error", "Token endpoint not configured")
                    .build());
        }
    }

    @Override
    public Uni<OidcTokenExchangeResponse> exchange(OidcTokenExchangeRequest request) {
        LOG.debugf("Exchanging authorization code with IdP: %s", request.tokenEndpoint());

        final var timeout = config.tokenExchange().timeout().toMillis();
        final var formBody = buildFormBody(request);

        var httpRequest = webClient
                .postAbs(request.tokenEndpoint())
                .timeout(timeout)
                .putHeader("Content-Type", "application/x-www-form-urlencoded")
                .putHeader("Accept", "application/json");

        // Add authentication header for client_secret_basic
        if (request.clientAuthMethod() == ClientAuthMethod.CLIENT_SECRET_BASIC && request.clientSecret() != null) {
            final var credentials = request.clientId() + ":" + request.clientSecret();
            final var encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            httpRequest = httpRequest.putHeader("Authorization", "Basic " + encoded);
        }

        return httpRequest
                .sendBuffer(Buffer.buffer(formBody))
                .flatMap(this::parseTokenResponse)
                .onFailure()
                .transform(error -> {
                    LOG.errorf(error, "Token exchange failed");
                    return GatewayProblem.badGateway("Token exchange with IdP failed: " + error.getMessage());
                });
    }

    private String buildFormBody(OidcTokenExchangeRequest request) {
        Map<String, String> params = new HashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", request.authorizationCode());

        if (request.redirectUri() != null && !request.redirectUri().isBlank()) {
            params.put("redirect_uri", request.redirectUri());
        }

        // Include PKCE verifier if present
        request.codeVerifier().ifPresent(verifier -> params.put("code_verifier", verifier));

        // For client_secret_post, include credentials in body
        if (request.clientAuthMethod() == ClientAuthMethod.CLIENT_SECRET_POST) {
            params.put("client_id", request.clientId());
            if (request.clientSecret() != null) {
                params.put("client_secret", request.clientSecret());
            }
        } else {
            // For client_secret_basic, still include client_id in body (some IdPs require it)
            params.put("client_id", request.clientId());
        }

        // Add scopes if provided
        request.scopes().ifPresent(scopes -> params.put("scope", scopes));

        return params.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    private Uni<OidcTokenExchangeResponse> parseTokenResponse(HttpResponse<Buffer> response) {
        if (response.statusCode() != 200) {
            final var body = response.bodyAsString();
            LOG.warnf("Token exchange failed with status %d: %s", response.statusCode(), body);
            return Uni.createFrom().failure(GatewayProblem.badGateway("IdP returned error: " + response.statusCode()));
        }

        try {
            final var json = response.bodyAsJsonObject();

            final var accessToken = json.getString("access_token");
            if (accessToken == null || accessToken.isBlank()) {
                return Uni.createFrom().failure(GatewayProblem.badGateway("IdP response missing access_token"));
            }

            final var tokenType = json.getString("token_type", "Bearer");
            final var expiresIn = json.getLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS);

            // Extract additional claims
            Map<String, Object> additionalClaims = new HashMap<>();
            json.fieldNames().stream()
                    .filter(this::isAdditionalClaim)
                    .forEach(key -> additionalClaims.put(key, json.getValue(key)));

            final var tokenResponse = new OidcTokenExchangeResponse(
                    accessToken,
                    Optional.ofNullable(json.getString("id_token")),
                    Optional.ofNullable(json.getString("refresh_token")),
                    tokenType,
                    expiresIn,
                    Optional.ofNullable(json.getString("scope")),
                    additionalClaims);

            LOG.debugf("Token exchange successful, expires_in: %d", expiresIn);
            return Uni.createFrom().item(tokenResponse);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse token response");
            return Uni.createFrom().failure(GatewayProblem.badGateway("Failed to parse IdP token response"));
        }
    }

    private boolean isAdditionalClaim(String key) {
        return !key.equals("access_token")
                && !key.equals("id_token")
                && !key.equals("refresh_token")
                && !key.equals("token_type")
                && !key.equals("expires_in")
                && !key.equals("scope");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
