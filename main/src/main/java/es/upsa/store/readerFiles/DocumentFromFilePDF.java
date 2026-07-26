package es.upsa.store.readerFiles;


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
public class DocumentFromFilePDF implements DocumentLoaderService {

    private static final Logger log = LoggerFactory.getLogger(DocumentFromFilePDF.class);

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
        // Sin el prefijo UUID de la subida: "a8a7c73a-..._PlayStation_5.pdf" -> "PlayStation_5.pdf"
        String nombreLimpio = pdfPath.getFileName().toString().replaceFirst("^[0-9a-fA-F-]{36}_", "");

        try (PDDocument pdf = PDDocument.load(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPaginas = pdf.getNumberOfPages();
            for (int pageNum = 1; pageNum <= totalPaginas; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String text = stripper.getText(pdf);

                // Antes: lanzar excepción rompía TODA la ingesta por una página en blanco.
                // Ahora: se omite la página y se avisa.
                if (text == null || text.isBlank()) {
                    log.warn("Página {} de '{}' sin texto; se omite.", pageNum, nombreLimpio);
                    continue;
                }

                // PDFBox alinea columnas con tabuladores/espacios ("dispone de      16      GB").
                // Esos huecos ensucian el embedding (peor score) y el prompt (más tokens).
                // Se colapsan espacios/tabs preservando los saltos de línea, que el
                // splitter recursivo usa para respetar párrafos.
                String textoLimpio = text.replaceAll("[^\\S\\n]+", " ").trim();

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("file", nombreLimpio);
                meta.put("page", pageNum);
                segments.add(Document.from(textoLimpio, Metadata.from(meta)));
            }
        }
        if (segments.isEmpty()) {
            log.warn("El PDF '{}' no aportó ninguna página con texto.", nombreLimpio);
        }
        return segments;
    }
}
