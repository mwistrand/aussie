package aussie.core.model.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Parsed inbound authentication transport shared by HTTP and route authentication. */
public record InboundCredentials(List<String> authorizationHeaders, Optional<String> sessionId) {

    private static final String BEARER = "Bearer ";

    public InboundCredentials {
        authorizationHeaders = authorizationHeaders == null ? List.of() : List.copyOf(authorizationHeaders);
        sessionId = sessionId == null ? Optional.empty() : sessionId;
    }

    public static InboundCredentials from(Map<String, List<String>> headers, String cookieName) {
        final var authorization = headers == null
                ? List.<String>of()
                : headers.entrySet().stream()
                        .filter(entry ->
                                entry.getKey() != null && entry.getKey().equalsIgnoreCase("Authorization"))
                        .flatMap(entry -> entry.getValue() == null ? Stream.empty() : entry.getValue().stream())
                        .toList();
        final var session = cookieName == null ? Optional.<String>empty() : findCookie(headers, cookieName);
        return new InboundCredentials(authorization, session);
    }

    public boolean hasConflictingCredentials() {
        return authorizationHeaders.size() > 1 || (!authorizationHeaders.isEmpty() && sessionId.isPresent());
    }

    public Optional<String> bearerToken() {
        if (authorizationHeaders.size() != 1) {
            return Optional.empty();
        }
        final var header = authorizationHeaders.getFirst();
        if (header == null
                || header.length() <= BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        return Optional.of(header.substring(BEARER.length()).trim());
    }

    private static Optional<String> findCookie(Map<String, List<String>> headers, String cookieName) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase("Cookie"))
                .flatMap(entry -> entry.getValue() == null ? Stream.empty() : entry.getValue().stream())
                .filter(Objects::nonNull)
                .flatMap(header -> Stream.of(header.split(";")))
                .map(String::trim)
                .filter(cookie -> cookie.startsWith(cookieName + "="))
                .map(cookie -> cookie.substring(cookieName.length() + 1).trim())
                .filter(value -> !value.isBlank())
                .findFirst();
    }
}
