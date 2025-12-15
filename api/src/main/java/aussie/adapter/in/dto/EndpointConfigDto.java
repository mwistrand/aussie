package aussie.adapter.in.dto;

import java.util.Optional;
import java.util.Set;

import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointType;
import aussie.core.model.routing.EndpointVisibility;

public record EndpointConfigDto(
        String path, Set<String> methods, String visibility, String pathRewrite, Boolean authRequired, String type) {

    public EndpointConfig toModel() {
        return toModel(false);
    }

    public EndpointConfig toModel(boolean defaultAuthRequired) {
        var vis = visibility != null ? EndpointVisibility.valueOf(visibility.toUpperCase()) : EndpointVisibility.PUBLIC;
        var auth = authRequired != null ? authRequired : defaultAuthRequired;
        var endpointType = type != null ? EndpointType.valueOf(type.toUpperCase()) : EndpointType.HTTP;

        return new EndpointConfig(path, methods, vis, Optional.ofNullable(pathRewrite), auth, endpointType);
    }

    public static EndpointConfigDto fromModel(EndpointConfig model) {
        return new EndpointConfigDto(
                model.path(),
                model.methods(),
                model.visibility().name(),
                model.pathRewrite().orElse(null),
                model.authRequired(),
                model.type().name());
    }
}
