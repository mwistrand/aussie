package aussie.adapter.in.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import aussie.adapter.in.dto.EndpointConfigDto;

/**
 * Validates that non-WebSocket endpoints have at least one HTTP method defined.
 */
public class EndpointMethodsValidator implements ConstraintValidator<ValidEndpointMethods, EndpointConfigDto> {

    @Override
    public boolean isValid(EndpointConfigDto dto, ConstraintValidatorContext context) {
        if (dto == null) {
            return true;
        }

        if ("WEBSOCKET".equalsIgnoreCase(dto.type())) {
            return true;
        }

        if (dto.methods() == null || dto.methods().isEmpty()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("at least one HTTP method is required")
                    .addPropertyNode("methods")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
