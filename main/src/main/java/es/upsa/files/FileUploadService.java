package es.upsa.files;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;


@ApplicationScoped
public class FileUploadService {

    @ConfigProperty(name = "rag.location.txt")
    Path txtDir;

    @ConfigProperty(name = "rag.location.csv")
    Path csvDir;

    @ConfigProperty(name = "rag.location.pdf")
    Path pdfDir;

    @POST
    @jakarta.ws.rs.Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_PLAIN)
    public Path subirArchivo(@RestForm("file") InputStream contenido,
                                 @RestForm("fileName") String nombreArchivo) throws IOException {

        if (contenido == null || nombreArchivo == null) {
            throw new IllegalArgumentException("Faltan datos");
        }

        Path dirDestino = resolveDir(getExtension(nombreArchivo));
        if (dirDestino == null) {
            throw new IllegalArgumentException("Extensión no válida");
        }

        Path carpetaBase = dirDestino.toAbsolutePath().normalize();
        Files.createDirectories(carpetaBase);

        String nombreSeguro = sanitizeFileName(nombreArchivo);
        Path destino = carpetaBase.resolve(UUID.randomUUID() + "_" + nombreSeguro).normalize();

        if (!destino.startsWith(carpetaBase)) {
            throw new IllegalArgumentException("Ruta de destino no válida");
        }

        try (OutputStream out = Files.newOutputStream(destino)) {
            contenido.transferTo(out);
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


}
