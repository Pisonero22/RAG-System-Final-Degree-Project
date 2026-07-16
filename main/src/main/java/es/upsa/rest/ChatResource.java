package es.upsa.rest;

import dev.langchain4j.agent.tool.P;
import es.upsa.providers.storages.RedisStorage;
import es.upsa.store.redis.IngestionRedisConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;

@ApplicationScoped
@Path("/service")
public class ChatResource {

    @Inject
    @RedisStorage
    IngestionRedisConfiguration ingestionRedisConfiguration;

    @GET
    @Path("/ingest")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getIngestResponse() throws IOException {

        ingestionRedisConfiguration.clearIngestionCache();
        ingestionRedisConfiguration.ingest();

        return Response.ok()
                .entity("Ingesta de datos en la base de datos")
                .build();
    }

    @GET
    @Path("/reset")
    @Produces(MediaType.APPLICATION_JSON)
    public Response resetRedis() throws IOException {
        ingestionRedisConfiguration.resetEmbeddingStore();
        return Response.ok()
                .entity("Storage reiniciada y documentos reingestados con éxito")
                .build();
    }

}
