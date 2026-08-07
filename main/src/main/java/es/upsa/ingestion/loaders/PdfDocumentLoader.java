package es.upsa.ingestion.loaders;


import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;


import org.apache.pdfbox.pdmodel.PDDocument;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@ApplicationScoped
public class PdfDocumentLoader implements DocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentLoader.class);


    private static final int BRIDGE_CHARS = 300;

    @Override
    public List<Document> load(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            log.warn("El directorio '{}' no existe; no hay PDFs que cargar.", folder);
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .forEach(p -> {
                        try {
                            documents.addAll(loadPdf(p));
                        } catch (Exception e) {
                            log.warn("PDF '{}' ilegible; se OMITE de la ingesta: {}",
                                    p.getFileName(), e.toString());
                        }
                    });
        }
        return documents;
    }

    @Override
    public List<Document> loadFile(Path file) throws IOException {
        return loadPdf(file);
    }

    private List<Document> loadPdf(Path pdfPath) throws IOException {
        // Sin el prefijo UUID de la subida: "a8a7c73a-..._PlayStation_5.pdf" -> "PlayStation_5.pdf"
        String cleanName = pdfPath.getFileName().toString().replaceFirst("^[0-9a-fA-F-]{36}_", "");
        // ---- FASE 1: extraer el text de las páginas que tienen text ----
        List<Integer> pageNumbers = new ArrayList<>();   // número REAL de página (para la cita)
        List<String>  pageTexts  = new ArrayList<>();

        try (PDDocument pdf = PDDocument.load(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPaginas = pdf.getNumberOfPages();

            for (int pageNum = 1; pageNum <= totalPaginas; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String text = stripper.getText(pdf);

                if (text == null || text.isBlank()) {
                    log.warn("Página {} de '{}' sin text; se omite.", pageNum, cleanName);
                    continue;
                }
                pageNumbers.add(pageNum);
                pageTexts.add((text.replaceAll("[^\\S\\n]+", " ").trim()));

            }
        }
        List<Document> segments = new ArrayList<>();
        for (int i = 0; i < pageTexts.size(); i++) {
            String bridge = (i == 0) ? "" : tail(pageTexts.get(i - 1), BRIDGE_CHARS);
            String content = bridge.isEmpty() ? pageTexts.get(i)
                    : bridge + " \n" + pageTexts.get(i);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("file", cleanName);
            metadata.put("page", pageNumbers.get(i));
            segments.add(Document.from(content, Metadata.from(metadata)));
        }

        if (segments.isEmpty()) {
            log.warn("El PDF '{}' no aportó ninguna página con text.", cleanName);
        }
        return segments;

    }

    /** Últimos maxChars caracteres, sin empezar a mitad de palabra. */
    private static String tail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String cut = text.substring(text.length() - maxChars);
        int firstSpace = cut.indexOf(' ');
        return (firstSpace >= 0) ? cut.substring(firstSpace + 1) : cut;
    }
}
