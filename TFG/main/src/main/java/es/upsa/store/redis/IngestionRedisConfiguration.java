package es.upsa.store.redis;

import static dev.langchain4j.data.document.splitter.DocumentSplitters.recursive;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import es.upsa.providers.storages.RedisStorage;
import es.upsa.store.StorageProvider;
import es.upsa.store.readerFiles.DocumentFromFileCSV;
import es.upsa.store.readerFiles.DocumentFromFilePDF;
import es.upsa.store.readerFiles.DocumentFromFileTxt;
import io.quarkiverse.langchain4j.redis.RedisEmbeddingStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.util.SafeEncoder;


import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@RedisStorage
public class IngestionRedisConfiguration implements StorageProvider {


    private static final Logger log = LoggerFactory.getLogger(IngestionRedisConfiguration.class);

    @Inject
    RedisEmbeddingStore embeddingStore;
    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    DocumentFromFileTxt documentFromFileTxt;
    @Inject
    DocumentFromFileCSV documentFromFileCSV;
    @Inject
    DocumentFromFilePDF documentFromFilePDF;

    @ConfigProperty(name = "rag.location.txt")
    Path txtFile;

    @ConfigProperty(name = "rag.location.csv")
    Path csvFiles;

    @ConfigProperty(name = "rag.location.pdf")
    Path pdfFiles;


    @Override
    public void clearIngestionCache() {
        // Abre una conexión a Redis usando Jedis (host local, puerto 6379)
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            // Busca todas las claves que comienzan con "embedding:"
            var keys = jedis.keys("embedding:*");
            // Si hay claves que coinciden con el patrón
            if (keys != null && !keys.isEmpty()) {
                // Elimina todas las claves encontradas (solo los documentos, pero no el índice RedisSearch)
                jedis.del(keys.toArray(new String[0]));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }




    @Override
    public void ingest () throws IOException {
        List<Document> files = new ArrayList<>();
        files.addAll(documentFromFileTxt.load(txtFile));
        files.addAll(documentFromFileCSV.load(csvFiles));
        files.addAll(documentFromFilePDF.load(pdfFiles));

        log.info("Se han cargado {} documentos entre txts, CSVs y PDFs desde {} + {} + {}"
                , files.size(), txtFile, csvFiles,pdfFiles);
        if (files.isEmpty()) {
            log.warn("No se han cargado documentos. Revisa el directorio y el contenido de los archivos.");
        }
        EmbeddingStoreIngestor storeIngestor = EmbeddingStoreIngestor.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .documentSplitter(recursive(256,64))
                .build();

        storeIngestor.ingest(files);
    }


    @Override
    public void resetEmbeddingStore() throws IOException {
        try (Jedis jedis = new Jedis("localhost", 6379)) {

            // Eliminar índice RediSearch manualmente
            try {
                jedis.getClient().sendCommand(new ProtocolCommand() {
                    @Override
                    public byte[] getRaw() {
                        return SafeEncoder.encode("FT.DROPINDEX");
                    }
                }, "embedding-index", "DD");

                log.info("Índice 'embedding-index' eliminado correctamente.");
            } catch (Exception e) {
                log.warn("No se pudo eliminar el índice (puede que no exista): {}", e.getMessage());
            }

           clearIngestionCache();

        } catch (Exception e) {
            log.error("Error al resetear Redis: ", e);
        }

        ingest();
    }



}
