package aussie.adapter.in.auth;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;

import aussie.core.model.auth.Permission;

final class SecurityIdentityFactory {

    private SecurityIdentityFactory() {}

    static SecurityIdentity create(Principal principal, Set<String> permissions, Map<String, ?> attributes) {
        final var effectivePermissions = permissions == null ? Set.<String>of() : Set.copyOf(permissions);
        final var roles = Permission.toRoles(effectivePermissions);
        final var builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(principal)
                .addRoles(roles)
                .addAttribute("permissions", effectivePermissions);

        roles.forEach(role -> builder.addPermission(new StringPermission(role)));
        attributes.forEach((name, value) -> builder.addAttribute(name, value));
        return builder.build();
    }

    static Map<String, Object> attributes(Object... namesAndValues) {
        final var attributes = new HashMap<String, Object>();
        for (var i = 0; i < namesAndValues.length; i += 2) {
            if (namesAndValues[i + 1] != null) {
                attributes.put((String) namesAndValues[i], namesAndValues[i + 1]);
            }
        }
        return attributes;
    }
}
