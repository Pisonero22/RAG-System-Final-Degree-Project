package es.upsa.rest;

import es.upsa.files.FileUploadService;
import es.upsa.providers.storages.RedisStorage;
import es.upsa.store.redis.IngestionRedisConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;

import java.io.IOException;
import java.io.InputStream;

@ApplicationScoped
@Path("/service")
public class ChatResource {

    @Inject
    @RedisStorage
    IngestionRedisConfiguration ingestionRedisConfiguration;
    @Inject
    FileUploadService fileUploadService;

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

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_PLAIN)
    public Response subirArchivo(@RestForm("file") InputStream archivo,
                                 @RestForm("fileName") String nombreArchivo) {
        try {
            java.nio.file.Path destino = fileUploadService.subirArchivo(archivo, nombreArchivo);
            return Response.ok("Archivo guardado en: " + destino).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (IOException e) {
            return Response.serverError().entity("Error al guardar: " + e.getMessage()).build();
        }
    }
}
