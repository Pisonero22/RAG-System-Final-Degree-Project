package es.upsa.store;

import java.io.IOException;

public interface StorageProvider {
    void clearIngestionCache();
    void ingest() throws IOException;
    void resetEmbeddingStore()throws IOException ;
}
