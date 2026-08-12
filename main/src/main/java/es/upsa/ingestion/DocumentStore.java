package es.upsa.ingestion;

import java.io.IOException;
import java.nio.file.Path;

public interface DocumentStore {
    /** Wipes every embedding and re-ingests the whole corpus from disk. */
    void rebuildIndex()throws IOException ;
    /** Ingests one file that has just been uploaded, leaving the rest of the index alone. */
    void ingestSingleFile(Path file) throws IOException;
}
