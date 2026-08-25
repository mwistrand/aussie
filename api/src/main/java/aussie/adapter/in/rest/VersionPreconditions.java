package aussie.adapter.in.rest;

import aussie.adapter.in.problem.GatewayProblem;

final class VersionPreconditions {

    private VersionPreconditions() {}

    static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        var value = ifMatch.trim();
        if (value.startsWith("\"") || value.endsWith("\"")) {
            if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
                throw GatewayProblem.badRequest("If-Match must contain a resource version");
            }
            value = value.substring(1, value.length() - 1);
        }
        try {
            var version = Long.parseLong(value);
            if (version < 1) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException invalid) {
            throw GatewayProblem.badRequest("If-Match must contain a resource version");
        }
    }

    static String etag(long version) {
        return "\"" + version + "\"";
    }
}
