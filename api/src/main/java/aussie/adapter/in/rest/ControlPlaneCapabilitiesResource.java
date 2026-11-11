package aussie.adapter.in.rest;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Authenticated discovery endpoint for the control-plane wire contract. */
@Path("/admin/capabilities")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ControlPlaneCapabilitiesResource {

    @GET
    public Capabilities get() {
        return new Capabilities(
                ControlPlaneContract.VERSION,
                List.of(ControlPlaneContract.VERSION),
                ControlPlaneContract.SPECIFICATION_PATH);
    }

    public record Capabilities(String contractVersion, List<String> supportedContractVersions, String openApi) {}
}
