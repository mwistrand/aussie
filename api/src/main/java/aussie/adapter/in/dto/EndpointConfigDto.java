package aussie.adapter.in.dto;

import java.util.Optional;
import java.util.Set;

import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;

public record EndpointConfigDto(
        String path, Set<String> methods, String visibility, String pathRewrite, Boolean authRequired) {
    public EndpointConfig toModel() {
        var vis = visibility != null ? EndpointVisibility.valueOf(visibility.toUpperCase()) : EndpointVisibility.PUBLIC;
        var auth = authRequired != null ? authRequired : false;

        return new EndpointConfig(path, methods, vis, Optional.ofNullable(pathRewrite), auth);
    }

    public static EndpointConfigDto fromModel(EndpointConfig model) {
        return new EndpointConfigDto(
                model.path(),
                model.methods(),
                model.visibility().name(),
                model.pathRewrite().orElse(null),
                model.authRequired());
    }
}
