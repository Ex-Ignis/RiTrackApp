package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.RiderUpdateDto;
import es.hargos.ritrack.service.RiderUpdateService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controlador para actualización de riders
 */
@RestController
@RequestMapping("/api/v1/riders")
@CrossOrigin(origins = "*")
public class RiderUpdateController {

    private static final Logger logger = LoggerFactory.getLogger(RiderUpdateController.class);

    private final RiderUpdateService riderUpdateService;

    @Autowired
    public RiderUpdateController(RiderUpdateService riderUpdateService) {
        this.riderUpdateService = riderUpdateService;
    }

    /**
     * Actualiza información de un rider
     * El frontend puede enviar solo los campos a modificar
     *
     * PUT /api/v1/riders/{riderId}
     */
    @PutMapping("/{riderId}")
    public ResponseEntity<?> updateRider(@PathVariable Integer riderId, @Valid @RequestBody RiderUpdateDto updateData) {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            error.put("message", "No se pudo determinar el tenant del usuario");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        logger.info("Tenant {}: Solicitud de actualización para rider {}", tenantId, riderId);

        try {
            // Validación básica
            if (riderId == null || riderId <= 0) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "ID de rider inválido");
                error.put("message", "El ID debe ser un número positivo");
                return ResponseEntity.badRequest().body(error);
            }

            // Llamar al servicio de actualización
            Map<String, Object> result = riderUpdateService.updateRider(tenantId, riderId, updateData);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Rider actualizado correctamente");
            response.put("riderId", riderId);
            response.put("data", result);

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            String message = e.getMessage();

            // Verificar si es un error de duplicado
            if (message != null && message.startsWith("DUPLICATE_")) {
                String[] parts = message.split(":", 2);
                Map<String, String> error = new HashMap<>();
                error.put("error", "Recurso duplicado");
                error.put("message", parts.length > 1 ? parts[1] : "El email o teléfono ya está en uso");
                error.put("code", parts[0]);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(error); // 409
            }

            // Verificar si es rider no encontrado
            if (message != null && message.contains("Rider no encontrado")) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Rider no encontrado");
                error.put("message", message);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error); // 404
            }

            // Error genérico
            logger.error("Error actualizando rider {}: {}", riderId, e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error al actualizar");
            error.put("message", "Error procesando la actualización");
            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            logger.error("Error actualizando rider {}: {}", riderId, e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error interno");
            error.put("message", "Error en el servidor");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}