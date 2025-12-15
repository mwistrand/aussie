package aussie.core.model.common;

public sealed interface ValidationResult {

    record Valid() implements ValidationResult {}

    record Invalid(String reason, int suggestedStatusCode) implements ValidationResult {}

    default boolean isValid() {
        return this instanceof Valid;
    }

    default boolean isInvalid() {
        return this instanceof Invalid;
    }

    static ValidationResult valid() {
        return new Valid();
    }

    static ValidationResult invalid(String reason, int statusCode) {
        return new Invalid(reason, statusCode);
    }

    static ValidationResult payloadTooLarge(String reason) {
        return new Invalid(reason, 413);
    }

    static ValidationResult headerTooLarge(String reason) {
        return new Invalid(reason, 431);
    }
}
