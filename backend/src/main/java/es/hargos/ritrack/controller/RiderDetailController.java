package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.RiderDetailDto;
import es.hargos.ritrack.service.RiderDetailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller para obtener información detallada de riders
 * Combina datos estáticos (Rooster) y dinámicos (Live)
 */
@RestController
@RequestMapping("/api/v1/riders")
@CrossOrigin(origins = "*") // Configurar según necesidades de seguridad
public class RiderDetailController {

    private static final Logger logger = LoggerFactory.getLogger(RiderDetailController.class);

    private final RiderDetailService riderDetailService;

    @Autowired
    public RiderDetailController(RiderDetailService riderDetailService) {
        this.riderDetailService = riderDetailService;
    }

    /**
     * Obtiene información completa de un rider específico
     *
     * Combina:
     * - Datos estáticos desde Rooster API (información personal, contrato, etc.)
     * - Datos dinámicos desde Live API (estado actual, entregas, rendimiento, etc.)
     *
     * @param riderId ID del rider (employee_id)
     * @return RiderDetailDto con toda la información disponible
     *
     * Ejemplo de uso:
     * GET /api/v1/riders/1234/details
     */
    @GetMapping("/{riderId}/details")
    public ResponseEntity<?> getRiderDetails(@PathVariable Integer riderId) {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            error.put("message", "No se pudo determinar el tenant del usuario");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        logger.info("Tenant {}: Solicitud de detalles completos para rider {}", tenantId, riderId);

        try {
            // Validación básica
            if (riderId == null || riderId <= 0) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "ID de rider inválido");
                error.put("message", "El ID debe ser un número positivo");
                return ResponseEntity.badRequest().body(error);
            }

            // Obtener datos completos
            RiderDetailDto riderDetails = riderDetailService.getRiderCompleteDetails(tenantId, riderId);

            // Verificar si se encontraron datos
            if (riderDetails == null || "NONE".equals(riderDetails.getDataSource())) {
                Map<String, String> notFound = new HashMap<>();
                notFound.put("error", "Rider no encontrado");
                notFound.put("message", "No se encontró información para el rider con ID " + riderId);
                notFound.put("riderId", riderId.toString());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound);
            }

            // Log del resultado
            logger.info("Detalles obtenidos para rider {}: fuente={}, completo={}",
                    riderId, riderDetails.getDataSource(), riderDetails.getIsDataComplete());

            // Agregar headers informativos
            return ResponseEntity.ok()
                    .header("X-Data-Source", riderDetails.getDataSource())
                    .header("X-Data-Complete", String.valueOf(riderDetails.getIsDataComplete()))
                    .body(riderDetails);

        } catch (Exception e) {
            logger.error("Error obteniendo detalles del rider {}: {}", riderId, e.getMessage(), e);

            Map<String, String> error = new HashMap<>();
            error.put("error", "Error interno del servidor");
            error.put("message", "Error al procesar la solicitud para el rider " + riderId);
            error.put("details", e.getMessage());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}