package es.upsa.store.readerFiles;


import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@ApplicationScoped
public class DocumentFromFileCSV implements DocumentLoaderService {

    private static final Logger log = LoggerFactory.getLogger(DocumentFromFilePDF.class);

    @Override
    public List<Document> load(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("La ruta debe ser un directorio: " + folder);
        }
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .forEach(p -> {
                        try {
                            documents.addAll(loadCsvFile(p));
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
        return loadCsvFile(file);
    }

    private List<Document> loadCsvFile(Path csvPath) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
        // Sin el prefijo UUID de la subida: "bd49ab9c-..._Productos.csv" -> "Productos.csv"
        String nombreLimpio = csvPath.getFileName().toString().replaceFirst("^[0-9a-fA-F-]{36}_", "");

        List<Document> documents = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(csvPath);
             CSVParser parser = new CSVParser(reader, format)) {
            int rowNum = 1;
            for (CSVRecord record : parser) {
                // Metadatos mínimos: fichero y fila. Las columnas van solo en el TEXTO.
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("nombre", nombreLimpio);
                map.put("fila", rowNum++);

                StringBuilder text = new StringBuilder();
                for (String header : parser.getHeaderNames()) {
                    String value = record.get(header);
                    text.append(header).append(": ").append(value).append("\n");
                }

                Metadata meta = Metadata.from(map);
                documents.add(Document.from(text.toString(), meta));
            }
        }
        if (documents.isEmpty()) {
            log.warn("CSV vacio");
            return List.of();
        }
        return documents;
    }
}