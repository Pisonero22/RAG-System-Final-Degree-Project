package es.upsa;



import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import es.upsa.configuration.ModelProvider;
import es.upsa.providers.llms.DeepSeekProvider;
import es.upsa.providers.llms.MistralProvider;
import es.upsa.providers.llms.OllamaProvider;
import es.upsa.providers.llms.OpenAIProvider;
import es.upsa.providers.storages.RedisStorage;
import es.upsa.ragConfiguration.RagAsssistant;
import es.upsa.ragConfiguration.RagRetriever;

import es.upsa.store.redis.IngestionRedisConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.*;
import org.jboss.resteasy.reactive.RestForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;


@ApplicationScoped
@Path("/service")
public class ServicioAI {


    @Inject
    RagRetriever ragRetriever;

    @Inject
    @OllamaProvider
    ModelProvider ollamaProvider;
    @Inject
    @OpenAIProvider
    ModelProvider openAIProvider;
    @Inject
    @DeepSeekProvider
    ModelProvider deepSeekProvider;
    @Inject
    @MistralProvider
    ModelProvider mistralProvider;
    @Inject
    @RedisStorage
    IngestionRedisConfiguration ingestionRedisConfiguration;



    @GET
    @Path("/ingest")
    @Produces(MediaType.APPLICATION_JSON)
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


    public String getIngestResponseWithRag(String message, String modelProvider) {
        RetrievalAugmentor retrievalAugmentor = ragRetriever.getRetrievalAugmentor();
        return AiServices.builder(RagAsssistant.class)
                    .chatLanguageModel(getProviderByName(modelProvider).getChatLanguageModel())
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(8))
                    .retrievalAugmentor(retrievalAugmentor)
                    .build()
                .augmentedChat(message);
    }

    //Select del front para elegir LLM
    public ModelProvider getProviderByName(String name) {
        return switch (name.toLowerCase()) {
            case "openai" -> openAIProvider;
            case "ollama" -> ollamaProvider;
            case "deepseek" -> deepSeekProvider;
            case "mistral" -> mistralProvider;
            default -> ollamaProvider;
        };
    }


    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_PLAIN)
    public Response subirArchivo(@RestForm("file") InputStream archivo,
                                 @RestForm("fileName") String nombreArchivo) {
        try {
            if (nombreArchivo == null || archivo == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Faltan datos").build();
            }

            String extension = getExtension(nombreArchivo);
            String carpetaDestino;

            if ("txt".equalsIgnoreCase(extension)) {
                carpetaDestino = "src/main/resources/rag/txt";
            } else if ("csv".equalsIgnoreCase(extension)) {
                carpetaDestino = "src/main/resources/rag/csv";
            } else if ("pdf".equalsIgnoreCase(extension)) {
                carpetaDestino = "src/main/resources/rag/pdf";
            } else {
                return Response.status(Response.Status.BAD_REQUEST).entity("Extensión no válida").build();
            }

            File carpeta = new File(carpetaDestino);
            if (!carpeta.exists()) carpeta.mkdirs();

            String nuevoNombre = UUID.randomUUID().toString() + "_" + nombreArchivo;
            File archivoDestino = new File(carpeta, nuevoNombre);

            try (OutputStream out = new FileOutputStream(archivoDestino)) {
                byte[] buffer = new byte[8192];
                int bytesLeidos;
                while ((bytesLeidos = archivo.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesLeidos);
                }
            }

            return Response.ok("Archivo guardado en: " + archivoDestino.getPath()).build();

        } catch (IOException e) {
            return Response.serverError().entity("Error al guardar: " + e.getMessage()).build();
        }
    }

    private String getExtension(String nombreArchivo) {
        int punto = nombreArchivo.lastIndexOf('.');
        return (punto > 0) ? nombreArchivo.substring(punto + 1) : "";
    }


}
