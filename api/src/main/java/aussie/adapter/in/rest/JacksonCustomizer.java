package aussie.adapter.in.rest;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.quarkus.jackson.ObjectMapperCustomizer;

/**
 * Customizes the Jackson ObjectMapper used by Quarkus REST endpoints.
 *
 * <p>Ensures proper support for Java records and Optional types.
 */
@Singleton
public class JacksonCustomizer implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper objectMapper) {
        // Register Jdk8Module for Optional support
        objectMapper.registerModule(new Jdk8Module());

        // Don't fail on unknown properties (for forward compatibility)
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
