package com.alphaledger.common.security;

import java.util.List;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** The caller of the current request, read from a validated access token. */
public record AuthenticatedUser(long id, String email, Set<String> roles) {

    public static AuthenticatedUser from(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No JWT-authenticated user in the current request");
        }
        List<String> roles = jwt.getClaimAsStringList(JwtClaims.ROLES);
        return new AuthenticatedUser(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString(JwtClaims.EMAIL),
                roles == null ? Set.of() : Set.copyOf(roles));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(Roles.ADMIN);
    }
}
