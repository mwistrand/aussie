package aussie.api.dto;

import java.util.Optional;
import java.util.Set;

import aussie.routing.model.EndpointConfig;
import aussie.routing.model.EndpointVisibility;

public record EndpointConfigDto(
    String path,
    Set<String> methods,
    String visibility,
    String pathRewrite
) {
    public EndpointConfig toModel() {
        var vis = visibility != null
            ? EndpointVisibility.valueOf(visibility.toUpperCase())
            : EndpointVisibility.PUBLIC;

        return new EndpointConfig(
            path,
            methods,
            vis,
            Optional.ofNullable(pathRewrite)
        );
    }

    public static EndpointConfigDto fromModel(EndpointConfig model) {
        return new EndpointConfigDto(
            model.path(),
            model.methods(),
            model.visibility().name(),
            model.pathRewrite().orElse(null)
        );
    }
}
