package aussie.adapter.in.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Validates that HTTP endpoints have at least one method defined.
 * WebSocket endpoints do not require methods.
 */
@Documented
@Constraint(validatedBy = EndpointMethodsValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEndpointMethods {
    String message() default "at least one HTTP method is required";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
