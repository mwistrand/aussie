package aussie.core.model.service;

/**
 * Result of a service registration attempt.
 *
 * <p>Encapsulates success with the registered service or failure with error details.
 */
public sealed interface RegistrationResult {

    /**
     * Create a successful result.
     *
     * @param registration The registered service
     * @return Success result
     */
    static RegistrationResult success(ServiceRegistration registration) {
        return new Success(registration);
    }

    /**
     * Create a failure result.
     *
     * @param reason Error description
     * @param statusCode Suggested HTTP status code
     * @return Failure result
     */
    static RegistrationResult failure(String reason, int statusCode) {
        return new Failure(reason, statusCode);
    }

    /**
     * Successful registration.
     *
     * @param registration The registered service
     */
    record Success(ServiceRegistration registration) implements RegistrationResult {}

    /**
     * Failed registration.
     *
     * @param reason Error description
     * @param statusCode Suggested HTTP status code (400 for validation, 403 for policy violations)
     */
    record Failure(String reason, int statusCode) implements RegistrationResult {}
}
