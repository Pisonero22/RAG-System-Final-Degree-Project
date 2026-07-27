package es.upsa.rest;

import es.upsa.files.FileUploadService;
import es.upsa.providers.storages.RedisStorage;
import es.upsa.security.AdminEndpoint;
import es.upsa.store.StorageProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;
import org.jboss.resteasy.reactive.RestForm;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
@Path("/service")
public class ChatResource {

    @Inject
    @RedisStorage
    StorageProvider storage;

    @Inject
    FileUploadService fileUploadService;
    @Inject
    Config config;

    @POST
    @Path("/ingest")
    @Produces(MediaType.TEXT_PLAIN)
    @AdminEndpoint
    public Response getIngestResponse() throws IOException {

        storage.resetEmbeddingStore();
        return Response.ok()
                .entity("Ingesta completada: almacén limpiado y documentos reingestados")
                .build();
    }

    @POST
    @Path("/reset")
    @Produces(MediaType.TEXT_PLAIN)
    @AdminEndpoint
    public Response resetRedis() throws IOException {
        storage.resetEmbeddingStore();
        return Response.ok()
                .entity("Storage reiniciada y documentos reingestados con éxito")
                .build();
    }

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_PLAIN)
    @AdminEndpoint
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

    /**
     * Modelos reales configurados en cada slot, para que la UI muestre
     * etiquetas verídicas (si OLLAMA_CHAT_MODEL cambia el modelo del slot
     * 'llama', el desplegable lo refleja sin tocar el HTML). Público a
     * propósito: la UI lo necesita antes de conectar y solo expone
     * identificadores de modelo, ningún secreto.
     */
    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> modelosDisponibles() {
        Map<String, String> modelos = new LinkedHashMap<>();
        modelos.put("llama", modeloOllama("llama"));
        modelos.put("gpt", config.getOptionalValue(
                        "quarkus.langchain4j.openai.gpt.chat-model.model-name", String.class)
                .orElse("desconocido"));
        modelos.put("qwen", modeloOllama("qwen"));
        modelos.put("gpto", modeloOllama("gpto"));
        modelos.put("deepseek", modeloOllama("deepseek"));
        modelos.put("mistral", modeloOllama("mistral"));
        return modelos;
    }

    private String modeloOllama(String slot) {
        return config.getOptionalValue(
                        "quarkus.langchain4j.ollama." + slot + ".chat-model.model-id", String.class)
                .orElse("desconocido");
    }
}
