package aussie.adapter.in.dto;

import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import aussie.core.model.auth.VisibilityRule;
import aussie.core.model.routing.EndpointVisibility;

/**
 * DTO for visibility rules in service registration requests.
 */
public record VisibilityRuleDto(
        @NotBlank(message = "pattern is required") String pattern,
        List<String> methods,
        @Pattern(regexp = "^(PUBLIC|PRIVATE)$", message = "visibility must be PUBLIC or PRIVATE") String visibility) {

    public VisibilityRule toModel() {
        var methodSet = methods != null ? Set.copyOf(methods) : Set.<String>of();
        var vis = visibility != null ? EndpointVisibility.valueOf(visibility.toUpperCase()) : EndpointVisibility.PUBLIC;
        return new VisibilityRule(pattern, methodSet, vis);
    }

    public static VisibilityRuleDto fromModel(VisibilityRule model) {
        return new VisibilityRuleDto(
                model.pattern(),
                model.methods().isEmpty() ? null : List.copyOf(model.methods()),
                model.visibility().name());
    }
}
