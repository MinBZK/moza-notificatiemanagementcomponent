package nl.rijksoverheid.moz.client.profielservice;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "profiel-service")
@Path("/api/profielservice/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface ProfielServiceClient {

    @POST
    @Path("/partij")
    PartijResponse zoekPartij(PartijRequest request);
}
