package es.upsa.files;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;

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


    public Path subirArchivo(@RestForm("file") InputStream contenido,
                                 @RestForm("fileName") String nombreArchivo) throws IOException {

        if (contenido == null || nombreArchivo == null) {
            throw new IllegalArgumentException("Faltan datos");
        }

        String extension = getExtension(nombreArchivo).toLowerCase();
        Path dirDestino = resolveDir(extension);
        if (dirDestino == null) {
            throw new IllegalArgumentException("Extensión no válida");
        }

        PushbackInputStream entrada = new PushbackInputStream(contenido, 5);
        if ("pdf".equals(extension)) {
            byte[] cabecera = entrada.readNBytes(5);
            entrada.unread(cabecera);
            if (!new String(cabecera, StandardCharsets.US_ASCII).startsWith("%PDF")) {
                throw new IllegalArgumentException("El archivo tiene extensión .pdf pero no es un PDF válido");
            }
        }

        Path carpetaBase = dirDestino.resolve("uploads").toAbsolutePath().normalize();
        Files.createDirectories(carpetaBase);

        String nombreSeguro = sanitizeFileName(nombreArchivo);
        Path destino = carpetaBase.resolve(UUID.randomUUID() + "_" + nombreSeguro).normalize();



        if (!destino.startsWith(carpetaBase)) {
            throw new IllegalArgumentException("Ruta de destino no válida");
        }

        try (OutputStream out = Files.newOutputStream(destino)) {
            entrada.transferTo(out);
        }
        return destino;


    }


    private String sanitizeFileName(String nombreArchivo) {
        // Se queda solo con el nombre base, ignorando cualquier ruta que venga incluida
        String base = Paths.get(nombreArchivo).getFileName().toString();
        // Solo permite alfanuméricos, puntos, guiones y guion bajo
        String limpio = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (limpio.isBlank() || limpio.equals(".") || limpio.equals("..")) {
            throw new IllegalArgumentException("Nombre de fichero no válido");
        }
        return limpio;
    }
    private Path resolveDir(String extension) {
        return switch (extension.toLowerCase()) {
            case "txt" -> txtDir;
            case "csv" -> csvDir;
            case "pdf" -> pdfDir;
            default -> null;
        };
    }

    private String getExtension(String nombreArchivo) {
        int punto = nombreArchivo.lastIndexOf('.');
        return (punto > 0) ? nombreArchivo.substring(punto + 1) : "";
    }
    public int borrarSubidas() throws IOException {
        int total = 0;
        for (Path dir : List.of(txtDir, csvDir, pdfDir)) {
            Path uploads = dir.resolve("uploads");
            if (!Files.isDirectory(uploads)) continue;
            try (Stream<Path> archivos = Files.list(uploads)) {
                for (Path p : archivos.filter(Files::isRegularFile).toList()) {
                    Files.delete(p);
                    total++;
                }
            }
        }
        return total;
    }


}
