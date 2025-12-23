package aussie.adapter.in.http;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.config.PkceConfig;
import aussie.core.service.auth.PkceService;

/**
 * REST endpoints for OIDC authorization flows with PKCE support.
 *
 * <p>Implements RFC 7636 (PKCE) to protect authorization code flows against
 * interception attacks. All authorization requests must include PKCE parameters
 * when PKCE is required (default).
 *
 * @see <a href="https://tools.ietf.org/html/rfc7636">RFC 7636 - PKCE</a>
 */
@Path("/auth/oidc")
@Produces(MediaType.APPLICATION_JSON)
public class OidcResource {

    private static final Logger LOG = Logger.getLogger(OidcResource.class);

    private final PkceService pkceService;
    private final PkceConfig pkceConfig;

    @Inject
    public OidcResource(PkceService pkceService, PkceConfig pkceConfig) {
        this.pkceService = pkceService;
        this.pkceConfig = pkceConfig;
    }

    /**
     * Initiate an OIDC authorization request.
     *
     * <p>This endpoint validates PKCE parameters and stores the challenge for
     * later verification during token exchange. It then redirects to the
     * configured identity provider.
     *
     * @param redirectUri The URI to redirect to after authentication
     * @param codeChallenge The PKCE code_challenge (required when PKCE is enabled)
     * @param codeChallengeMethod The challenge method (must be "S256")
     * @param clientState Optional client-provided state for CSRF protection
     * @param idpUrl The identity provider authorization URL
     * @return Redirect to identity provider or error response
     */
    @GET
    @Path("/authorize")
    public Uni<Response> authorize(
            @QueryParam("redirect_uri") String redirectUri,
            @QueryParam("code_challenge") String codeChallenge,
            @QueryParam("code_challenge_method") String codeChallengeMethod,
            @QueryParam("state") String clientState,
            @QueryParam("idp_url") String idpUrl) {

        if (!pkceConfig.enabled()) {
            throw GatewayProblem.featureDisabled("PKCE");
        }

        // Validate redirect URI
        if (redirectUri == null || redirectUri.isBlank()) {
            throw GatewayProblem.badRequest("redirect_uri is required");
        }
        validateUrl(redirectUri, "redirect_uri");

        // Validate IdP URL
        if (idpUrl == null || idpUrl.isBlank()) {
            throw GatewayProblem.badRequest("idp_url is required");
        }
        validateUrl(idpUrl, "idp_url");

        // Validate PKCE parameters
        if (pkceService.isRequired()) {
            if (codeChallenge == null || codeChallenge.isBlank()) {
                LOG.debug("PKCE required but code_challenge not provided");
                throw GatewayProblem.badRequest("PKCE with S256 challenge method is required");
            }

            if (!pkceService.isValidChallengeMethod(codeChallengeMethod)) {
                LOG.debugf("Invalid challenge method: %s", codeChallengeMethod);
                throw GatewayProblem.badRequest("Only S256 challenge method is supported");
            }
        }

        // Generate state parameter for CSRF protection
        final var state = pkceService.generateState();

        // Store PKCE challenge if provided
        Uni<Void> storeChallenge;
        if (codeChallenge != null && !codeChallenge.isBlank()) {
            storeChallenge = pkceService.storeChallenge(state, codeChallenge);
        } else {
            storeChallenge = Uni.createFrom().voidItem();
        }

        return storeChallenge.map(v -> {
            LOG.debugf("Initiating OIDC authorization with state: %s", state);

            // Build IdP authorization URL
            String authUrl = buildIdpUrl(idpUrl, state, redirectUri, clientState);

            return Response.seeOther(URI.create(authUrl)).build();
        });
    }

    /**
     * Exchange authorization code for tokens with PKCE verification.
     *
     * <p>This endpoint verifies the PKCE code_verifier against the stored
     * challenge before completing the token exchange.
     *
     * @param code The authorization code from the IdP
     * @param codeVerifier The PKCE code_verifier (required when PKCE was used)
     * @param state The state parameter from the authorization request
     * @param redirectUri The redirect URI (must match the authorization request)
     * @return Token response or error
     */
    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Uni<Response> exchangeToken(
            @FormParam("code") String code,
            @FormParam("code_verifier") String codeVerifier,
            @FormParam("state") String state,
            @FormParam("redirect_uri") String redirectUri) {

        if (!pkceConfig.enabled()) {
            throw GatewayProblem.featureDisabled("PKCE");
        }

        // Validate required parameters
        if (code == null || code.isBlank()) {
            throw GatewayProblem.badRequest("code is required");
        }

        if (state == null || state.isBlank()) {
            throw GatewayProblem.badRequest("state is required");
        }

        // Verify PKCE
        if (pkceService.isRequired()) {
            if (codeVerifier == null || codeVerifier.isBlank()) {
                LOG.debug("PKCE required but code_verifier not provided");
                throw GatewayProblem.badRequest("code_verifier is required");
            }
        }

        // Verify PKCE if verifier is provided
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            return pkceService.verifyChallenge(state, codeVerifier).flatMap(valid -> {
                if (!valid) {
                    LOG.warnf("PKCE verification failed for state: %s", state);
                    throw GatewayProblem.badRequest("PKCE verification failed");
                }

                LOG.debugf("PKCE verification successful for state: %s", state);
                return completeTokenExchange(code, redirectUri);
            });
        }

        // No verifier provided and PKCE is optional - proceed with token exchange
        return completeTokenExchange(code, redirectUri);
    }

    /**
     * Complete the token exchange with the identity provider.
     *
     * <p>This method should be extended to actually exchange the code
     * with the IdP. For now, it returns a placeholder response.
     */
    private Uni<Response> completeTokenExchange(String code, String redirectUri) {
        // TODO: Implement actual token exchange with IdP
        LOG.debugf("Token exchange initiated for code: %s", code.substring(0, Math.min(8, code.length())) + "...");

        return Uni.createFrom()
                .item(Response.ok(Map.of(
                                "message", "Token exchange successful",
                                "note", "Actual IdP token exchange not yet implemented"))
                        .build());
    }

    /**
     * Build the IdP authorization URL with all required parameters.
     */
    private String buildIdpUrl(String baseUrl, String state, String redirectUri, String clientState) {
        StringBuilder url = new StringBuilder(baseUrl);

        // Append ? or & depending on whether URL already has query params
        url.append(baseUrl.contains("?") ? "&" : "?");
        url.append("state=").append(urlEncode(state));
        url.append("&redirect_uri=").append(urlEncode(redirectUri));

        // Include original client state if provided (for client-side CSRF)
        if (clientState != null && !clientState.isBlank()) {
            url.append("&client_state=").append(urlEncode(clientState));
        }

        return url.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void validateUrl(String url, String paramName) {
        try {
            final var uri = URI.create(url);
            if (uri.getScheme() == null
                    || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))) {
                throw GatewayProblem.badRequest(paramName + " must use http or https scheme");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw GatewayProblem.badRequest(paramName + " must have a valid host");
            }
        } catch (IllegalArgumentException e) {
            throw GatewayProblem.badRequest(paramName + " is not a valid URL");
        }
    }
}
