package aussie.core.port.out;

/**
 * Provides authenticated principal attributes for the current request.
 */
public interface AuthenticatedContext {

    /**
     * Get the team ID from the authenticated principal, if available.
     *
     * @return team ID, or null if the request is unauthenticated or the principal has no team
     */
    String getTeamId();
}
