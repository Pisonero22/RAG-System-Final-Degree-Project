package es.upsa.rest;

import es.upsa.upload.FileUploadService;
import es.upsa.ingestion.RedisStorage;
import es.upsa.security.AdminEndpoint;
import es.upsa.ingestion.DocumentStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

@ApplicationScoped
@Path("/service/admin")
@AdminEndpoint
public class AdminResource {

    private static final Logger log = LoggerFactory.getLogger(AdminResource.class);

    @Inject
    @RedisStorage
    DocumentStore storage;

    @Inject
    FileUploadService fileUploadService;


    @POST
    @Path("/reset")
    @Produces(MediaType.TEXT_PLAIN)
    public Response rebuildIndex() throws IOException {
        try {
            storage.rebuildIndex();
            return Response.ok()
                    .entity("Storage reiniciada y documentos reingestados con éxito")
                    .build();
        }catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (IOException e) {
            log.error("Reset failed", e);
            return Response.serverError().entity("Reset failed: " + e.getMessage()).build();
        }
    }

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_PLAIN)
    public Response upload(@RestForm("file") InputStream archivo,
                           @RestForm("fileName") String nombreArchivo) throws IOException {


        java.nio.file.Path destino;
        try {
            destino = fileUploadService.store(archivo, nombreArchivo);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (IOException e) {
            return Response.serverError().entity("Error al guardar el archivo: " + e.getMessage()).build();
        }

        try {
            storage.ingestSingleFile(destino);
        } catch (Exception e) {
            // El archivo YA está en disco: si no se puede ingestar, se retira para que
            // no ensucie las ingestas completas posteriores.
            Files.deleteIfExists(destino);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("El archivo no se ha podido procesar y se ha descartado: " + e.getMessage())
                    .build();
        }
        return Response.ok("Archivo guardado e ingerido: " + destino.getFileName()).build();

    }


    @POST
    @Path("/clean-uploads")
    @Produces(MediaType.TEXT_PLAIN)
    public Response cleanUploads() throws IOException {
        int borrados;
        try {
            borrados = fileUploadService.deleteUploads();
        } catch (IOException e) {
            log.error("Failed to delete uploads", e);
            return Response.serverError().entity("Error al borrar las subidas: " + e.getMessage()).build();
        }

        try {
            storage.rebuildIndex();
        } catch (IllegalStateException e) {
            // Los ficheros YA se han borrado: hay que avisar de que el índice quedó sin reconstruir.
            return Response.status(Response.Status.CONFLICT)
                    .entity("Subidas eliminadas: " + borrados + ", pero hay un reindexado en curso "
                            + "y el índice NO se ha reconstruido. Pulsa Reset cuando termine.")
                    .build();
        } catch (IOException e) {
            log.error("Reindex after cleaning uploads failed", e);
            return Response.serverError()
                    .entity("Subidas eliminadas: " + borrados + ", pero el reindexado falló: " + e.getMessage())
                    .build();
        }
        return Response.ok("Subidas eliminadas: " + borrados + ". Índice reconstruido desde el corpus base.").build();
    }
}
