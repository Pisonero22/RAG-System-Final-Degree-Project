package es.upsa.ingestion.loaders;

import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocumentsRecursively;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

@ApplicationScoped
public class TxtDocumentLoader implements DocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(TxtDocumentLoader.class);

    public List<Document> load(Path filePath) throws IOException {
        File file = filePath.toFile();

        if (!file.exists()) {
            log.warn("El directorio '{}' no existe; no hay TXTs que cargar.", filePath);
            return List.of();
        }
        if (!file.canRead()) {
            log.warn("No se puede leer el fichero o directorio: '{}'", filePath);
            return List.of();
        }
        PathMatcher onlyTxt = FileSystems.getDefault().getPathMatcher("glob:**.txt");
        List<Document> documents = loadDocumentsRecursively(filePath, onlyTxt, new TextDocumentParser());

        if (documents == null || documents.isEmpty()) {
            log.warn("No hay documentos de text en '{}'.", filePath);
            return List.of();
        }
        // Documento en blanco: se omite, no se aborta el lote.
        return documents.stream()
                .filter(d -> d.text() != null && !d.text().isBlank())
                .toList();

    }

    @Override
    public List<Document> loadFile(Path txtPath) throws IOException {

        Document doc = FileSystemDocumentLoader.loadDocument(txtPath, new TextDocumentParser());

        if (doc == null || doc.text() == null || doc.text().isBlank()) {
            throw new IllegalArgumentException("Empty or unreadable file: " + txtPath.getFileName());
        }
        return List.of(doc);

    }
}
