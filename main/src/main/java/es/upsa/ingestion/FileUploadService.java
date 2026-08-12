package es.upsa.ingestion;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;


@ApplicationScoped
public class FileUploadService {

    @ConfigProperty(name = "rag.location.txt")
    Path txtDir;

    @ConfigProperty(name = "rag.location.csv")
    Path csvDir;

    @ConfigProperty(name = "rag.location.pdf")
    Path pdfDir;


    public Path store(InputStream content, String fileName) throws IOException {

        if (content == null || fileName == null) {
            throw new IllegalArgumentException("Missing data");
        }

        String ext = getExtension(fileName).toLowerCase();
        Path targetDir = resolveDir(ext);
        if (targetDir == null) {
            throw new IllegalArgumentException("Invalid extension");
        }
        // A .pdf extension proves nothing. Check the header, and push the bytes back so the copy
        // below still writes the complete file.
        PushbackInputStream input = new PushbackInputStream(content, 5);
        if ("pdf".equals(ext)) {
            byte[] magicBytes = input.readNBytes(5);
            input.unread(magicBytes);
            if (!new String(magicBytes, StandardCharsets.US_ASCII).startsWith("%PDF")) {
                throw new IllegalArgumentException("The file has a .pdf extension but is not a valid PDF");
            }
        }

        Path uploadsDir = targetDir.resolve("uploads").toAbsolutePath().normalize();
        Files.createDirectories(uploadsDir);

        String safeName = sanitizeFileName(fileName);
        Path target = uploadsDir.resolve(UUID.randomUUID() + "_" + safeName).normalize();



        if (!target.startsWith(uploadsDir)) {
            throw new IllegalArgumentException("Invalid destination path");
        }

        try (OutputStream out = Files.newOutputStream(target)) {
            input.transferTo(out);
        }
        return target;


    }


    private String sanitizeFileName(String fileName) {
        // Base name only: a browser can send "../../etc/passwd" as the file name.
        String base = Paths.get(fileName).getFileName().toString();
        String sanitized = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            throw new IllegalArgumentException("Invalid file name: " + fileName );
        }
        return sanitized;
    }
    private Path resolveDir(String ext) {
        return switch (ext.toLowerCase()) {
            case "txt" -> txtDir;
            case "csv" -> csvDir;
            case "pdf" -> pdfDir;
            default -> null;
        };
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex > 0) ? fileName.substring(dotIndex + 1) : "";
    }
    public int deleteUploads() throws IOException {
        int deletedCount = 0;
        for (Path dir : List.of(txtDir, csvDir, pdfDir)) {
            Path uploads = dir.resolve("uploads");
            if (!Files.isDirectory(uploads)) continue;
            try (Stream<Path> files = Files.list(uploads)) {
                for (Path p : files.filter(Files::isRegularFile).toList()) {
                    Files.delete(p);
                    deletedCount++;
                }
            }
        }
        return deletedCount;
    }


}
