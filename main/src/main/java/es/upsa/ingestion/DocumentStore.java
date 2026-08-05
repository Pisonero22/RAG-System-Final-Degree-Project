package es.upsa.ingestion;

import java.io.IOException;
import java.nio.file.Path;

public interface DocumentStore {
    void rebuildIndex()throws IOException ;
    void ingestSingleFile(Path file) throws IOException;
}
