package es.hargos.ritrack.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Servicio para gestionar almacenamiento de archivos .pem de tenants.
 *
 * Responsabilidades:
 * - Guardar archivos .pem en el filesystem
 * - Validar formato y contenido de archivos
 * - Generar rutas únicas por tenant
 */
@Service
public class FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);

    @Value("${file.storage.pem-keys-directory:./keys}")
    private String keysDirectory;

    /**
     * Guarda un archivo .pem para un tenant específico.
     *
     * @param tenantId ID del tenant
     * @param pemFile Archivo .pem subido por el usuario
     * @return Ruta relativa del archivo guardado (ej: "./keys/tenant_1.pem")
     * @throws IOException Si falla el guardado del archivo
     * @throws IllegalArgumentException Si el archivo no es válido
     */
    public String savePemFile(Long tenantId, MultipartFile pemFile) throws IOException {
        logger.info("Guardando archivo .pem para tenant {}", tenantId);

        // Validar que el archivo no esté vacío
        if (pemFile == null || pemFile.isEmpty()) {
            throw new IllegalArgumentException("El archivo .pem está vacío");
        }

        // Validar extensión
        String originalFilename = pemFile.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pem")) {
            throw new IllegalArgumentException("El archivo debe tener extensión .pem");
        }

        // Validar contenido básico (debe contener BEGIN/END PRIVATE KEY)
        String content = new String(pemFile.getBytes());
        if (!content.contains("BEGIN") || !content.contains("PRIVATE KEY")) {
            throw new IllegalArgumentException("El archivo .pem no parece contener una clave privada válida");
        }

        // Crear directorio si no existe
        Path keysPath = Paths.get(keysDirectory);
        if (!Files.exists(keysPath)) {
            Files.createDirectories(keysPath);
            logger.info("Directorio de keys creado: {}", keysPath.toAbsolutePath());
        }

        // Generar nombre de archivo único para el tenant
        String filename = String.format("tenant_%d.pem", tenantId);
        Path targetPath = keysPath.resolve(filename);

        // Guardar archivo (sobrescribe si existe)
        Files.copy(pemFile.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        // Generar ruta relativa para guardar en BD
        String relativePath = keysDirectory + "/" + filename;

        logger.info("Archivo .pem guardado exitosamente: {}", relativePath);

        return relativePath;
    }

    /**
     * Valida que un archivo .pem existe en el filesystem.
     *
     * @param pemPath Ruta del archivo .pem
     * @return true si el archivo existe y es legible
     */
    public boolean validatePemFileExists(String pemPath) {
        try {
            Path path = Paths.get(pemPath);
            boolean exists = Files.exists(path) && Files.isReadable(path);

            if (!exists) {
                logger.warn("Archivo .pem no encontrado o no legible: {}", pemPath);
            }

            return exists;
        } catch (Exception e) {
            logger.error("Error validando archivo .pem: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Lee el contenido de un archivo .pem.
     *
     * @param pemPath Ruta del archivo .pem
     * @return Contenido del archivo como String
     * @throws IOException Si falla la lectura
     */
    public String readPemFile(String pemPath) throws IOException {
        Path path = Paths.get(pemPath);
        return Files.readString(path);
    }

    /**
     * Elimina un archivo .pem del filesystem.
     *
     * @param pemPath Ruta del archivo .pem
     * @return true si se eliminó correctamente
     */
    public boolean deletePemFile(String pemPath) {
        try {
            Path path = Paths.get(pemPath);
            boolean deleted = Files.deleteIfExists(path);

            if (deleted) {
                logger.info("Archivo .pem eliminado: {}", pemPath);
            } else {
                logger.warn("Archivo .pem no existía: {}", pemPath);
            }

            return deleted;
        } catch (IOException e) {
            logger.error("Error eliminando archivo .pem {}: {}", pemPath, e.getMessage());
            return false;
        }
    }
}
