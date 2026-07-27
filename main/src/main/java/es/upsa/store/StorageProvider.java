package es.upsa.store;

import java.io.IOException;
import java.nio.file.Path;

public interface StorageProvider {
    void clearIngestionCache();
    void ingest() throws IOException;
    void resetEmbeddingStore()throws IOException ;
    void ingestFile(Path file) throws IOException;
}
