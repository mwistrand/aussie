package aussie.adapter.in.dto;

import java.util.List;
import java.util.Set;

import aussie.core.model.EndpointVisibility;
import aussie.core.model.VisibilityRule;

/**
 * DTO for visibility rules in service registration requests.
 */
public record VisibilityRuleDto(String pattern, List<String> methods, String visibility) {

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
