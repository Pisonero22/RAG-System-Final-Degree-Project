package es.upsa.rest;

import es.upsa.ingestion.FileUploadService;
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
    public Response rebuildIndex(){
        try {
            storage.rebuildIndex();
            return Response.ok()
                    .entity("Storage reset and documents re-ingested successfully")
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
    public Response upload(@RestForm("file") InputStream fileStream,
                           @RestForm("fileName") String fileName) throws IOException {


        java.nio.file.Path storedFile;
        try {
            storedFile = fileUploadService.store(fileStream, fileName);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (IOException e) {
            return Response.serverError().entity("Could not save the file: " + e.getMessage()).build();
        }

        try {
            storage.ingestSingleFile(storedFile);
        } catch (Exception e) {
            // The file is already on disk: if it cannot be ingested, take it back out so it does
            // not pollute later full reingests.
            Files.deleteIfExists(storedFile);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("The file could not be processed and was discarded: " + e.getMessage())
                    .build();
        }
        return Response.ok("File saved and ingested: " + storedFile.getFileName()).build();

    }


    @POST
    @Path("/clean-uploads")
    @Produces(MediaType.TEXT_PLAIN)
    public Response cleanUploads(){
        int deleted;
        try {
            deleted = fileUploadService.deleteUploads();
        } catch (IOException e) {
            log.error("Failed to delete uploads", e);
            return Response.serverError().entity("Could not delete the uploads: " + e.getMessage()).build();
        }

        try {
            storage.rebuildIndex();
        } catch (IllegalStateException e) {
            // The files are already gone: the caller has to be told the index was left unrebuilt.
            return Response.status(Response.Status.CONFLICT)
                    .entity("Uploads deleted: " + deleted + ", but a reindex is already running and the index has NOT been rebuilt. " +
                            "Press Reset once it finishes.")
                    .build();
        } catch (IOException e) {
            log.error("Reindex after cleaning uploads failed", e);
            return Response.serverError()
                    .entity("Uploads deleted: " + deleted + ", but the reindex failed: " + e.getMessage())
                    .build();
        }
        return Response.ok("Uploads deleted: " + deleted + ". Index rebuilt from the base corpus.").build();
    }
}
