package es.upsa;



import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import es.upsa.configuration.ModelProvider;
import es.upsa.providers.llms.DeepSeekProvider;
import es.upsa.providers.llms.MistralProvider;
import es.upsa.providers.llms.OllamaProvider;
import es.upsa.providers.llms.OpenAIProvider;
import es.upsa.providers.storages.RedisStorage;
import es.upsa.ragconfiguration.RagAssistant;
import es.upsa.ragconfiguration.RagRetriever;

import es.upsa.store.ChatMemoryStore;
import es.upsa.store.redis.IngestionRedisConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.*;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;

import java.nio.file.Files;

import java.nio.file.Paths;
import java.nio.file.Path;

import java.util.UUID;


@ApplicationScoped
@jakarta.ws.rs.Path("/service")
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

    @Inject
    ChatMemoryStore chatMemoryStore;

    @ConfigProperty(name = "rag.location.txt")
    Path txtDir;

    @ConfigProperty(name = "rag.location.csv")
    Path csvDir;

    @ConfigProperty(name = "rag.location.pdf")
    Path pdfDir;


    @GET
    @jakarta.ws.rs.Path("/ingest")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getIngestResponse() throws IOException {

        ingestionRedisConfiguration.clearIngestionCache();
        ingestionRedisConfiguration.ingest();

        return Response.ok()
                .entity("Ingesta de datos en la base de datos")
                .build();
    }

    @GET
    @jakarta.ws.rs.Path("/reset")
    @Produces(MediaType.APPLICATION_JSON)
    public Response resetRedis() throws IOException {
        ingestionRedisConfiguration.resetEmbeddingStore();
        return Response.ok()
                .entity("Storage reiniciada y documentos reingestados con éxito")
                .build();
    }


    public String getIngestResponseWithRag(String username, String message, String modelProvider) {
        RetrievalAugmentor retrievalAugmentor = ragRetriever.getRetrievalAugmentor();
        return AiServices.builder(RagAssistant.class)
                    .chatLanguageModel(getProviderByName(modelProvider).getChatLanguageModel())
                    .chatMemory(chatMemoryStore.getOrCreate(username))
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


    private String sanitizeFileName(String nombreArchivo) {
        // Se queda solo con el nombre base, ignorando cualquier ruta que venga incluida
        String base = Paths.get(nombreArchivo).getFileName().toString();
        // Solo permite alfanuméricos, puntos, guiones y guion bajo
        String limpio = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (limpio.isBlank() || limpio.equals(".") || limpio.equals("..")) {
            throw new IllegalArgumentException("Nombre de fichero no válido");
        }
        return limpio;
    }
    private Path resolveDir(String extension) {
        return switch (extension.toLowerCase()) {
            case "txt" -> txtDir;
            case "csv" -> csvDir;
            case "pdf" -> pdfDir;
            default -> null;
        };
    }
    @POST
    @jakarta.ws.rs.Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_PLAIN)
    public Response subirArchivo(@RestForm("file") InputStream archivo,
                                 @RestForm("fileName") String nombreArchivo) {
        try {
            if (nombreArchivo == null || archivo == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Faltan datos").build();
            }

            String extension = getExtension(nombreArchivo);

            // Obtenemos la ruta inyectada
            Path rutaConfigurada = resolveDir(extension);

            if (rutaConfigurada == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Extensión no válida").build();
            }

            // Usamos la ruta obtenida en lugar del texto hardcodeado
            Path carpetaBase = rutaConfigurada.toAbsolutePath().normalize();
            Files.createDirectories(carpetaBase);

            String nombreSeguro;
            try {
                nombreSeguro = sanitizeFileName(nombreArchivo);
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
            }

            Path destino = carpetaBase.resolve(UUID.randomUUID() + "_" + nombreSeguro).normalize();

            if (!destino.startsWith(carpetaBase)) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Ruta de destino no válida").build();
            }

            try (OutputStream out = new FileOutputStream(destino.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesLeidos;
                while ((bytesLeidos = archivo.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesLeidos);
                }
            }

            return Response.ok("Archivo guardado en: " + destino).build();

        } catch (IOException e) {
            return Response.serverError().entity("Error al guardar: " + e.getMessage()).build();
        }
    }

    private String getExtension(String nombreArchivo) {
        int punto = nombreArchivo.lastIndexOf('.');
        return (punto > 0) ? nombreArchivo.substring(punto + 1) : "";
    }


}
