package es.upsa.store.readerFiles;


import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@ApplicationScoped
public class DocumentFromFileCSV implements DocumentLoaderService {

    @Override
    public List<Document> load(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("La ruta debe ser un directorio: " + folder);
        }
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .forEach(p -> {
                        try {
                            documents.addAll(loadCsvFile(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException("Error leyendo CSV " + p, e);
                        }
                    });
        }

        return documents;
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
            throw new IllegalStateException("No se encontraron filas en el CSV: " + csvPath);
        }
        return documents;
    }
}