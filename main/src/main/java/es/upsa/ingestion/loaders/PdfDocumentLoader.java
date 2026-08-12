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

    /** How much of the previous page is prepended to each page. A sentence, a table or a
     *  procedure that runs across the page break stays whole in at least one chunk. */
    private static final int BRIDGE_CHARS = 300;

    @Override
    public List<Document> load(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            log.warn("Directory '{}' does not exist; no PDF files to load", folder);
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
                            log.warn("PDF '{}' unreadable; SKIPPED from the ingest: {}", p.getFileName(), e.toString());
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
        // Drops the UUID prefix uploads add: "a8a7c73a-..._PlayStation_5.pdf" -> "PlayStation_5.pdf"
        String cleanName = pdfPath.getFileName().toString().replaceFirst("^[0-9a-fA-F-]{36}_", "");

        List<Integer> pageNumbers = new ArrayList<>();   // REAL page number: blank pages are skipped, so the index drifts from it
        List<String>  pageTexts  = new ArrayList<>();

        try (PDDocument pdf = PDDocument.load(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = pdf.getNumberOfPages();

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String text = stripper.getText(pdf);

                if (text == null || text.isBlank()) {
                    log.debug("Page {} of '{}' has no text; skipped", pageNum, cleanName);
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
            log.warn("PDF '{}' produced no page with text", cleanName);
        }
        return segments;

    }

    /** The last maxChars characters, without starting mid-word. */
    private static String tail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String cut = text.substring(text.length() - maxChars);
        int firstSpace = cut.indexOf(' ');
        return (firstSpace >= 0) ? cut.substring(firstSpace + 1) : cut;
    }
}
