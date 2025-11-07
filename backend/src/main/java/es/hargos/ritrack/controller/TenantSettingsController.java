package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.UpdateSettingsRequest;
import es.hargos.ritrack.service.TenantSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller para actualización de configuración de tenant (sin credenciales sensibles).
 *
 * Endpoints:
 * - PATCH /api/v1/tenant/settings - Actualización parcial de configuración
 *
 * Para actualizar credenciales Glovo (clientId, keyId, pemFile),
 * usar el endpoint PUT /api/v1/tenant/credentials
 */
@RestController
@RequestMapping("/api/v1/tenant/settings")
public class TenantSettingsController {

    private static final Logger logger = LoggerFactory.getLogger(TenantSettingsController.class);

    private final TenantSettingsService settingsService;

    public TenantSettingsController(TenantSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Actualiza configuración de tenant parcialmente.
     * Solo actualiza los campos que vienen en el request (no-null).
     *
     * Uso:
     * PATCH /api/v1/tenant/settings
     * {
     *   "activeCityIds": [902, 804, 882]  // Solo actualiza ciudades
     * }
     *
     * PATCH /api/v1/tenant/settings
     * {
     *   "emailDomain": "entregalia",
     *   "emailBase": "gmail.com",
     *   "nameBase": "Entregalia"
     * }
     *
     * @param request DTO con campos opcionales a actualizar
     * @return Success response
     */
    @PatchMapping
    public ResponseEntity<?> updateSettings(@RequestBody UpdateSettingsRequest request) {
        try {
            // Obtener tenantId del contexto (desde JWT)
            TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
            Long tenantId = tenantInfo != null ? tenantInfo.getFirstTenantId() : null;

            if (tenantId == null) {
                logger.warn("Intento de actualización de settings sin tenantId");
                Map<String, String> error = new HashMap<>();
                error.put("error", "Tenant no encontrado");
                error.put("message", "No se pudo determinar el tenant del usuario");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }

            // Validar que al menos un campo viene para actualizar
            if (!request.hasAnyUpdate()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Sin cambios");
                error.put("message", "Debe proporcionar al menos un campo para actualizar");
                return ResponseEntity.badRequest().body(error);
            }

            logger.info("Tenant {}: Actualizando settings", tenantId);

            // Actualizar settings
            settingsService.updateSettings(tenantId, request);

            // Respuesta exitosa
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Configuración actualizada correctamente");

            logger.info("Tenant {}: Settings actualizados exitosamente", tenantId);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // Errores de validación (tenant no encontrado, etc.)
            logger.error("Error de validación: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Validación fallida");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            // Errores inesperados
            logger.error("Error actualizando settings: {}", e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error interno");
            error.put("message", "Ocurrió un error al actualizar la configuración");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Obtiene configuración actual del tenant.
     *
     * GET /api/v1/tenant/settings
     *
     * @return Configuración actual
     */
    @GetMapping
    public ResponseEntity<?> getSettings() {
        try {
            TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
            Long tenantId = tenantInfo != null ? tenantInfo.getFirstTenantId() : null;

            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    Map.of("error", "Tenant no encontrado")
                );
            }

            Map<String, Object> settings = new HashMap<>();
            settings.put("activeCityIds", settingsService.getActiveCityIds(tenantId));
            settings.put("emailDomain", settingsService.getEmailDomain(tenantId));
            settings.put("emailBase", settingsService.getEmailBase(tenantId));
            settings.put("nameBase", settingsService.getNameBase(tenantId));
            settings.put("defaultVehicleTypeIds", settingsService.getDefaultVehicleTypeIds(tenantId));

            return ResponseEntity.ok(settings);

        } catch (Exception e) {
            logger.error("Error obteniendo settings: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("error", "Error obteniendo configuración")
            );
        }
    }
}
