package aussie.adapter.in.rest;

import aussie.adapter.in.problem.GatewayProblem;

/** Shared bounds for administrative list endpoints. */
final class AdminPagination {

    static final int MAX_LIMIT = 100;
    static final int MAX_OFFSET = 100_000;

    private AdminPagination() {}

    static int boundedLimit(Integer limit) {
        return limit == null || limit < 1 ? MAX_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    static void validate(int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw GatewayProblem.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw GatewayProblem.badRequest("offset must be between 0 and " + MAX_OFFSET);
        }
    }
}
