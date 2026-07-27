package es.upsa.store.readerFiles;

import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocumentsRecursively;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

@ApplicationScoped
public class DocumentFromFileTxt implements DocumentLoaderService{
    public List<Document> load(Path filePath) throws IOException {
        File file = filePath.toFile();
        if (!file.exists()) {
            throw new IllegalArgumentException(
                    "El fichero o directorio no existe: " + filePath
            );
        }
        if (!file.canRead()) {
            throw new IllegalStateException(
                    "No se puede leer el fichero o directorio: " + filePath
            );
        }
        PathMatcher soloTxt = FileSystems.getDefault().getPathMatcher("glob:**.txt");
        List<Document> documents = loadDocumentsRecursively(filePath, soloTxt, new TextDocumentParser());
        if (documents == null || documents.isEmpty()) {
            throw new IllegalStateException(
                    "No se pudo crear ningún documento a partir de: " + filePath
            );
        }
        for (Document doc : documents) {
            if (doc.text() == null || doc.text().trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Se encontró un documento sin contenido de texto válido."
                );
            }
        }
        return documents;
    }

    @Override
    public List<Document> loadFile(Path txtPath) throws IOException {
        return List.of(FileSystemDocumentLoader.loadDocument(txtPath, new TextDocumentParser()));
    }
}
