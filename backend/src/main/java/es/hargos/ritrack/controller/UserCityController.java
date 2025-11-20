package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.request.AssignCitiesRequest;
import es.hargos.ritrack.dto.response.UserCityAssignmentResponse;
import es.hargos.ritrack.service.UserCityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controlador para gestionar asignaciones de ciudades a usuarios.
 * Permite a los TENANT_ADMIN configurar qué ciudades puede ver cada usuario.
 *
 * Base path: /api/v1/user-cities
 *
 * Endpoints:
 * - GET  /me                     - Obtener ciudades del usuario actual
 * - GET  /{userId}               - Obtener ciudades de un usuario (TENANT_ADMIN)
 * - POST /assign                 - Asignar ciudades a usuario (TENANT_ADMIN)
 * - DELETE /{userId}/cities/{cityId} - Quitar ciudad (TENANT_ADMIN)
 * - DELETE /{userId}             - Quitar todas las ciudades (TENANT_ADMIN)
 * - GET  /all                    - Listar todos usuarios con ciudades (TENANT_ADMIN)
 *
 * @author RiTrack Team
 * @version 2.1.0
 * @since 2025-11-19
 */
@RestController
@RequestMapping("/api/v1/user-cities")
@RequiredArgsConstructor
public class UserCityController {

    private static final Logger logger = LoggerFactory.getLogger(UserCityController.class);

    private final UserCityService userCityService;
    private final es.hargos.ritrack.repository.TenantRepository tenantRepository;

    /**
     * Obtiene las ciudades asignadas al usuario actual.
     * Cualquier usuario autenticado puede consultar sus propias ciudades.
     *
     * GET /api/v1/user-cities/me
     *
     * Response example:
     * {
     *   "userId": 123,
     *   "userEmail": "juan@arendel.com",
     *   "assignedCityIds": [902, 804],
     *   "assignedCities": []
     * }
     *
     * Si assignedCityIds está vacío, el usuario puede ver todas las ciudades.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMyCities() {
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

        if (tenantInfo == null || tenantInfo.getUserId() == null) {
            logger.warn("No se pudo obtener userId del contexto");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("No se pudo autenticar el usuario"));
        }

        Long userId = tenantInfo.getUserId();
        String userEmail = tenantInfo.getEmail();

        logger.info("Usuario ID {} ({}) consultando sus ciudades asignadas", userId, userEmail);

        try {
            UserCityAssignmentResponse response = userCityService.getUserCitiesWithEmail(userId, userEmail);

            logger.info("Usuario ID {} tiene {} ciudades asignadas",
                    userId, response.getAssignedCityIds().size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error obteniendo ciudades del usuario ID {}: {}",
                    userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error obteniendo ciudades asignadas"));
        }
    }

    /**
     * Obtiene las ciudades asignadas a un usuario específico.
     * Solo TENANT_ADMIN puede consultar ciudades de otros usuarios.
     *
     * GET /api/v1/user-cities/{userId}
     *
     * @param userId ID del usuario a consultar
     *
     * TODO: Añadir validación de rol TENANT_ADMIN con @PreAuthorize
     */
    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserCities(@PathVariable Long userId) {
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

        if (tenantInfo == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("No autenticado"));
        }

        logger.info("TENANT_ADMIN (user ID {}) consultando ciudades del usuario ID {}",
                tenantInfo.getUserId(), userId);

        try {
            UserCityAssignmentResponse response = userCityService.getUserCities(userId);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error obteniendo ciudades del usuario ID {}: {}",
                    userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error obteniendo ciudades del usuario"));
        }
    }

    /**
     * Asigna ciudades a un usuario (reemplaza las existentes).
     * Solo TENANT_ADMIN puede asignar ciudades.
     *
     * POST /api/v1/user-cities/assign
     *
     * Request body:
     * {
     *   "userId": 123,
     *   "cityIds": [902, 804]
     * }
     *
     * Response: 200 OK con mensaje de éxito
     *
     * TODO: Añadir @PreAuthorize("hasRole('TENANT_ADMIN')")
     */
    @PostMapping("/assign")
    public ResponseEntity<?> assignCities(@Valid @RequestBody AssignCitiesRequest request) {
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

        if (tenantInfo == null || tenantInfo.getUserId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("No autenticado"));
        }

        Long assignedByUserId = tenantInfo.getUserId();

        logger.info("TENANT_ADMIN (user ID {}) asignando {} ciudades al usuario ID {}",
                assignedByUserId, request.getCityIds().size(), request.getUserId());

        try {
            userCityService.assignCitiesToUser(request, assignedByUserId);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Ciudades asignadas correctamente");
            response.put("userId", request.getUserId());
            response.put("citiesCount", request.getCityIds().size());

            logger.info("✅ Ciudades asignadas exitosamente: {} ciudades a usuario ID {}",
                    request.getCityIds().size(), request.getUserId());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error asignando ciudades a usuario ID {}: {}",
                    request.getUserId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error asignando ciudades: " + e.getMessage()));
        }
    }

    /**
     * Elimina una ciudad específica de un usuario.
     * Solo TENANT_ADMIN puede eliminar ciudades.
     *
     * DELETE /api/v1/user-cities/{userId}/cities/{cityId}
     *
     * @param userId ID del usuario
     * @param cityId ID de la ciudad a eliminar
     *
     * TODO: Añadir @PreAuthorize("hasRole('TENANT_ADMIN')")
     */
    @DeleteMapping("/{userId}/cities/{cityId}")
    public ResponseEntity<?> removeCity(
            @PathVariable Long userId,
            @PathVariable Long cityId) {

        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

        if (tenantInfo == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("No autenticado"));
        }

        logger.info("TENANT_ADMIN (user ID {}) eliminando ciudad {} del usuario ID {}",
                tenantInfo.getUserId(), cityId, userId);

        try {
            userCityService.removeCityFromUser(userId, cityId);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Ciudad eliminada correctamente");
            response.put("userId", userId);
            response.put("cityId", cityId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error eliminando ciudad {} del usuario ID {}: {}",
                    cityId, userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error eliminando ciudad"));
        }
    }

    /**
     * Elimina todas las ciudades de un usuario.
     * Después de esto, el usuario podrá ver todas las ciudades del tenant.
     * Solo TENANT_ADMIN puede ejecutar esta acción.
     *
     * DELETE /api/v1/user-cities/{userId}
     *
     * @param userId ID del usuario
     *
     * TODO: Añadir @PreAuthorize("hasRole('TENANT_ADMIN')")
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<?> removeAllCities(@PathVariable Long userId) {
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

        if (tenantInfo == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("No autenticado"));
        }

        logger.info("TENANT_ADMIN (user ID {}) eliminando todas las ciudades del usuario ID {}",
                tenantInfo.getUserId(), userId);

        try {
            userCityService.removeAllCitiesFromUser(userId);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Todas las ciudades eliminadas. El usuario ahora puede ver todas las ciudades.");
            response.put("userId", userId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error eliminando todas las ciudades del usuario ID {}: {}",
                    userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error eliminando ciudades"));
        }
    }

    /**
     * Lista todos los usuarios con sus ciudades asignadas.
     * Para panel de administración de TENANT_ADMIN.
     *
     * GET /api/v1/user-cities/all
     *
     * Response example:
     * [
     *   {
     *     "userId": 123,
     *     "assignedCityIds": [902, 804]
     *   },
     *   {
     *     "userId": 456,
     *     "assignedCityIds": [882]
     *   }
     * ]
     *
     * TODO: Añadir @PreAuthorize("hasRole('TENANT_ADMIN')")
     */
    @GetMapping("/all")
    public ResponseEntity<?> getAllUsersWithCities() {
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

        if (tenantInfo == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("No autenticado"));
        }

        // Obtener RiTrack tenant ID del contexto
        Long ritrackTenantId = tenantInfo.getSelectedTenantId();

        logger.info("🔍 DEBUG - TenantContext info:");
        logger.info("  - User ID: {}", tenantInfo.getUserId());
        logger.info("  - Email: {}", tenantInfo.getEmail());
        logger.info("  - Selected Tenant ID (RiTrack): {}", tenantInfo.getSelectedTenantId());
        logger.info("  - First Tenant ID: {}", tenantInfo.getFirstTenantId());

        if (ritrackTenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("No se pudo determinar el tenant"));
        }

        // Buscar el tenant en RiTrack para obtener el hargos_tenant_id
        var tenant = tenantRepository.findById(ritrackTenantId)
                .orElseThrow(() -> new RuntimeException("Tenant no encontrado en RiTrack: " + ritrackTenantId));

        Long hargosTenantId = tenant.getHargosTenantId();

        if (hargosTenantId == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("El tenant no tiene hargos_tenant_id configurado"));
        }

        logger.info("  - RiTrack Tenant ID: {}, HargosAuth Tenant ID: {}", ritrackTenantId, hargosTenantId);

        logger.info("📞 TENANT_ADMIN (user ID {}) consultando usuarios del tenant {} desde HargosAuth",
                tenantInfo.getUserId(), hargosTenantId);

        try {
            List<UserCityAssignmentResponse> users = userCityService.getAllUsersWithCities(hargosTenantId);

            logger.info("✅ Encontrados {} usuarios en el tenant (con/sin asignaciones)", users.size());

            return ResponseEntity.ok(users);

        } catch (Exception e) {
            logger.error("Error obteniendo lista de usuarios con ciudades: {}",
                    e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Error obteniendo lista de usuarios"));
        }
    }

    /**
     * Helper method para crear respuestas de error consistentes
     */
    private Map<String, String> createErrorResponse(String message) {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return error;
    }
}
