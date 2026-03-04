package aussie.adapter.in.auth;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import io.quarkus.security.identity.SecurityIdentity;

import aussie.core.port.out.AuthenticatedContext;

/**
 * Adapts Quarkus {@link SecurityIdentity} to the core {@link AuthenticatedContext} port.
 */
@RequestScoped
public class SecurityIdentityContext implements AuthenticatedContext {

    private final SecurityIdentity securityIdentity;

    @Inject
    public SecurityIdentityContext(SecurityIdentity securityIdentity) {
        this.securityIdentity = securityIdentity;
    }

    @Override
    public String getTeamId() {
        if (securityIdentity.isAnonymous()) {
            return null;
        }
        return securityIdentity.getAttribute("teamId");
    }
}
