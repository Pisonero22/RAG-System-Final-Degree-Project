package es.upsa.qualifier.loaders;

import dev.langchain4j.data.document.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface DocumentLoader {
    List<Document> load(Path filePath) throws IOException;
    List<Document> loadFile(Path file) throws IOException;
}
