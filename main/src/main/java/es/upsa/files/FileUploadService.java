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
    public Response subirArchivo(@RestForm("file") InputStream archivo,
                                 @RestForm("fileName") String nombreArchivo) {
        try {
            if (nombreArchivo == null || archivo == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Faltan datos").build();
            }

            String extension = getExtension(nombreArchivo);

            Path rutaConfigurada = resolveDir(extension);

            if (rutaConfigurada == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Extensión no válida").build();
            }

            Path carpetaBase = rutaConfigurada.toAbsolutePath().normalize();
            Files.createDirectories(carpetaBase);

            String nombreSeguro;
            try {
                nombreSeguro = sanitizeFileName(nombreArchivo);
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
            }

            Path destino = carpetaBase.resolve(UUID.randomUUID() + "_" + nombreSeguro).normalize();

            if (!destino.startsWith(carpetaBase)) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Ruta de destino no válida").build();
            }

            try (OutputStream out = new FileOutputStream(destino.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesLeidos;
                while ((bytesLeidos = archivo.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesLeidos);
                }
            }

            return Response.ok("Archivo guardado en: " + destino).build();

        } catch (IOException e) {
            return Response.serverError().entity("Error al guardar: " + e.getMessage()).build();
        }
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
