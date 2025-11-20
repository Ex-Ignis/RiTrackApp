package es.hargos.ritrack.controller;

import es.hargos.ritrack.service.ApiMonitoringService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller para monitoreo de API y rate limiting.
 *
 * Endpoints protegidos con @PreAuthorize("hasRole('SUPER_ADMIN')").
 * Solo usuarios con rol SUPER_ADMIN pueden acceder.
 *
 * Endpoints:
 * - GET /stats: Estadísticas de todos los tenants
 * - GET /stats/{tenantId}: Estadísticas de un tenant específico
 * - DELETE /stats: Limpiar todas las estadísticas
 * - DELETE /stats/{tenantId}: Limpiar estadísticas de un tenant
 */
@RestController
@RequestMapping("/api/v1/monitoring")
public class ApiMonitoringController {

    private final ApiMonitoringService monitoringService;

    @Autowired
    public ApiMonitoringController(ApiMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    /**
     * Obtiene estadísticas de rate limiting de todos los tenants (resumen básico).
     *
     * Solo accesible por SUPER_ADMIN.
     *
     * GET /api/v1/monitoring/stats
     *
     * @return Estadísticas globales con resumen por tenant
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/stats")
    public ResponseEntity<?> getAllTenantsStatistics() {
        try {
            Map<String, Object> stats = monitoringService.getAllTenantsStatistics();
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error obteniendo estadísticas");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Obtiene estadísticas DETALLADAS de rate limiting de TODOS los tenants.
     * Incluye clasificación por endpoints y servicios para cada tenant.
     *
     * Solo accesible por SUPER_ADMIN.
     *
     * GET /api/v1/monitoring/stats/detailed
     *
     * Respuesta incluye:
     * - Total global de errores 429
     * - Clasificación GLOBAL por endpoint (todos los tenants)
     * - Clasificación GLOBAL por servicio (todos los tenants)
     * - Detalles por tenant:
     *   - Total errores del tenant
     *   - Errores clasificados por endpoint
     *   - Errores clasificados por servicio
     *   - Errores última hora / últimos 10 minutos
     *
     * @return Estadísticas detalladas de todos los tenants
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/stats/detailed")
    public ResponseEntity<?> getAllTenantsDetailedStatistics() {
        try {
            Map<String, Object> stats = monitoringService.getAllTenantsDetailedStatistics();
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error obteniendo estadísticas detalladas");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Obtiene estadísticas detalladas de rate limiting para un tenant específico.
     *
     * Solo accesible por SUPER_ADMIN.
     *
     * GET /api/v1/monitoring/stats/{tenantId}
     *
     * @param tenantId ID del tenant
     * @return Estadísticas detalladas del tenant
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/stats/{tenantId}")
    public ResponseEntity<?> getTenantStatistics(@PathVariable Long tenantId) {
        try {
            Map<String, Object> stats = monitoringService.getTenantStatistics(tenantId);
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error obteniendo estadísticas del tenant");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Limpia todas las estadísticas de rate limiting de todos los tenants.
     *
     * Solo accesible por SUPER_ADMIN.
     *
     * DELETE /api/v1/monitoring/stats
     *
     * @return Mensaje de confirmación
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/stats")
    public ResponseEntity<?> clearAllStatistics() {
        try {
            monitoringService.clearAllEvents();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Todas las estadísticas han sido limpiadas");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error limpiando estadísticas");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Limpia estadísticas de rate limiting de un tenant específico.
     *
     * Solo accesible por SUPER_ADMIN.
     *
     * DELETE /api/v1/monitoring/stats/{tenantId}
     *
     * @param tenantId ID del tenant
     * @return Mensaje de confirmación
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/stats/{tenantId}")
    public ResponseEntity<?> clearTenantStatistics(@PathVariable Long tenantId) {
        try {
            monitoringService.clearTenantEvents(tenantId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("tenantId", tenantId);
            response.put("message", "Estadísticas del tenant limpiadas");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error limpiando estadísticas del tenant");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
