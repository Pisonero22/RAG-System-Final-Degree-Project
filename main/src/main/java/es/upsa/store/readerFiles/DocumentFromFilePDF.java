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


    private static final int PUENTE_CHARS = 300;

    @Override
    public List<Document> load(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            log.warn("El directorio '{}' no existe; no hay CSVs que cargar.", folder);
            return List.of();
        }
        List<Document> docs = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .forEach(p -> {
                        try {
                            docs.addAll(loadPdf(p));
                        } catch (Exception e) {
                            log.warn("PDF '{}' ilegible; se OMITE de la ingesta: {}",
                                    p.getFileName(), e.toString());
                        }
                    });
        }
        return docs;
    }

    @Override
    public List<Document> loadFile(Path file) throws IOException {
        return loadPdf(file);
    }

    private List<Document> loadPdf(Path pdfPath) throws IOException {
        // Sin el prefijo UUID de la subida: "a8a7c73a-..._PlayStation_5.pdf" -> "PlayStation_5.pdf"
        String nombreLimpio = pdfPath.getFileName().toString().replaceFirst("^[0-9a-fA-F-]{36}_", "");
        // ---- FASE 1: extraer el texto de las páginas que tienen texto ----
        List<Integer> numeros = new ArrayList<>();   // número REAL de página (para la cita)
        List<String>  textos  = new ArrayList<>();

        try (PDDocument pdf = PDDocument.load(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPaginas = pdf.getNumberOfPages();

            for (int pageNum = 1; pageNum <= totalPaginas; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String text = stripper.getText(pdf);

                if (text == null || text.isBlank()) {
                    log.warn("Página {} de '{}' sin texto; se omite.", pageNum, nombreLimpio);
                    continue;
                }
                numeros.add(pageNum);
                textos.add(text.replaceAll("[^\\S\\n]+", " ").trim());
            }
        }
        List<Document> segments = new ArrayList<>();
        for (int i = 0; i < textos.size(); i++) {
            String puente = (i == 0) ? "" : cola(textos.get(i - 1), PUENTE_CHARS);
            String contenido = puente.isEmpty() ? textos.get(i)
                    : puente + "\n" + textos.get(i);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("file", nombreLimpio);
            meta.put("page", numeros.get(i));
            segments.add(Document.from(contenido, Metadata.from(meta)));
        }

        if (segments.isEmpty()) {
            log.warn("El PDF '{}' no aportó ninguna página con texto.", nombreLimpio);
        }
        return segments;

    }

    /** Últimos maxChars caracteres, sin empezar a mitad de palabra. */
    private static String cola(String texto, int maxChars) {
        if (texto.length() <= maxChars) {
            return texto;
        }
        String recorte = texto.substring(texto.length() - maxChars);
        int primerEspacio = recorte.indexOf(' ');
        return (primerEspacio >= 0) ? recorte.substring(primerEspacio + 1) : recorte;
    }
}
