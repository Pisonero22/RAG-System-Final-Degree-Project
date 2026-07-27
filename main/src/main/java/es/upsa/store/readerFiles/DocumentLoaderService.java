package es.upsa.store.readerFiles;

import dev.langchain4j.data.document.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface DocumentLoaderService {
    List<Document> load(Path filePath) throws IOException;
    List<Document> loadFile(Path file) throws IOException;
}
