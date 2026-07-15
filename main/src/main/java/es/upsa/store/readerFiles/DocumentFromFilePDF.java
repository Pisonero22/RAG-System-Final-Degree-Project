package es.upsa.store.readerFiles;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.text.PDFTextStripper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@ApplicationScoped
public class DocumentFromFilePDF implements DocumentLoaderService {

    @Override
    public List<Document> load(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("La ruta debe ser un directorio: " + folder);
        }
        List<Document> docs = new ArrayList<>();
        try (Stream<Path> paths = Files.list(folder)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .forEach(p -> {
                        try {
                            docs.addAll(loadPdf(p));
                        } catch (IOException e) {
                            throw new RuntimeException("Error leyendo PDF " + p, e);
                        }
                    });
        }
        return docs;
    }

    private List<Document> loadPdf(Path pdfPath) throws IOException {
        List<Document> segments = new ArrayList<>();
        try (PDDocument pdf = PDDocument.load(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDPageTree pages = pdf.getPages();
            int pageNum = 1;
            for (PDPage page : pages) {
                // Extrae solo texto de la página
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String text = stripper.getText(pdf);
                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException(
                            "PDF sin contenido de texto válido en página " + pageNum + ": " + pdfPath);
                }
                // Metadatos con nombre de archivo y número de página
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("file", pdfPath.getFileName().toString());
                meta.put("page", pageNum);
                segments.add(Document.from(text, Metadata.from(meta)));
                pageNum++;
            }
        }
        return segments;
    }
}
