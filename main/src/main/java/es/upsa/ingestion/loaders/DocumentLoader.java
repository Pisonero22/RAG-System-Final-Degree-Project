package es.upsa.ingestion.loaders;

import dev.langchain4j.data.document.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
/**
 * Two entry points on purpose. load() walks a directory and skips whatever it cannot read,
 * because one broken PDF must not cancel a full reingest; loadFile() takes a single file the
 * user has just uploaded and throws, because there the failure has somebody waiting for it.
 */
public interface DocumentLoader {
    List<Document> load(Path filePath) throws IOException;
    List<Document> loadFile(Path file) throws IOException;
}
