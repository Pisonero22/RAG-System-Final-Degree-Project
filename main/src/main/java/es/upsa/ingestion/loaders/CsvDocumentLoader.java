package es.upsa.ingestion.loaders;


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
/**
 * One CSV row -> one document. Rows are ingested without a splitter, so a row is never cut in
 * half and a price never ends up separated from the product it belongs to.
 */
@ApplicationScoped
public class CsvDocumentLoader implements DocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(CsvDocumentLoader.class);

    @Override
    public List<Document> load(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            log.warn("Directory '{}' does not exist; no CSV files to load", folder);
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .forEach(p -> {
                        try {
                            documents.addAll(loadCsvFile(p));
                        } catch (Exception e) {
                            log.warn("CSV '{}' unreadable; SKIPPED from the ingest: {}", p.getFileName(), e.toString());
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
        // Drops the UUID prefix uploads add: "bd49ab9c-..._Productos.csv" -> "Productos.csv"
        String cleanName = csvPath.getFileName().toString().replaceFirst("^[0-9a-fA-F-]{36}_", "");

        List<Document> documents = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(csvPath);
             CSVParser parser = new CSVParser(reader, format)) {
            int rowNum = 1;
            for (CSVRecord record : parser) {
                try{

                    // Minimal metadata: file and row. The columns go into the TEXT only, which is the
                    // field both searches actually look at.
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("nombre", cleanName);
                fields.put("fila", rowNum);
                StringBuilder text = new StringBuilder();
                    for (String header : parser.getHeaderNames()) {
                        String value = record.get(header);
                        // Underscores in the headers ("ID_Producto", "Precio_Base_EUR") are not
                        // separators for RediSearch: the whole header lands as a single term
                        // ("precio_base_eur") and words like "precio" or "producto" become
                        // impossible to reach with the lexical search. Swapping them for spaces
                        // also nudges the embedding slightly in the right direction.
                        text.append(header.replace('_', ' ')).append(": ").append(value).append(" \n ");
                    }

                Metadata metadata = Metadata.from(fields);
                documents.add(Document.from(text.toString(), metadata));
                }catch (Exception e) {
                    log.warn("CSV '{}', row {}: unreadable record, skipped ({})", cleanName, rowNum, e.toString());
                }
                rowNum++;
            }
        }
        if (documents.isEmpty()) {
            log.warn("CSV '{}' has no data rows; skipped", cleanName);
            return List.of();
        }
        return documents;
    }
}

