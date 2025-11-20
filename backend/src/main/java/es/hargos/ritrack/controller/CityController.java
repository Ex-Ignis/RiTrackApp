package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.StartingPointDto;
import es.hargos.ritrack.service.CityService;
import es.hargos.ritrack.service.UserCityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller for city-related operations
 * Base path: /api/v1/cities
 */
@RestController
@RequestMapping("/api/v1/cities")
public class CityController {

    private static final Logger logger = LoggerFactory.getLogger(CityController.class);

    @Autowired
    private CityService cityService;

    @Autowired
    private UserCityService userCityService;

    /**
     * Get starting points for a city
     *
     * GET /api/v1/cities/{cityId}/starting-points
     *
     * @param cityId City ID
     * @return List of starting points with 200 OK
     *         or error with appropriate status code
     *
     * Example response:
     * [
     *   {"id": 171, "name": "Bcn_3324", "cityId": 804},
     *   {"id": 333, "name": "Bcn_3325", "cityId": 804}
     * ]
     *
     * Security: Validates that the user has access to the requested city
     * Cache: 30 days TTL (starting points rarely change)
     * Multi-tenant: Uses TenantContext (ThreadLocal)
     */
    @GetMapping("/{cityId}/starting-points")
    public ResponseEntity<?> getStartingPoints(@PathVariable Integer cityId) {
        logger.info("Received request for starting points: cityId={}", cityId);

        // Extract tenant and user from context
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;
        Long userId = tenantInfo != null ? tenantInfo.getUserId() : null;

        if (tenantId == null) {
            logger.warn("Tenant ID not found in context");
            Map<String, Object> error = new HashMap<>();
            error.put("error", "No se pudo determinar el tenant");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        if (userId == null) {
            logger.warn("User ID not found in context");
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Usuario no autenticado");
            error.put("message", "No se pudo determinar el ID del usuario");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            // Security check: Validate user has access to this city
            List<Long> userCityIds = userCityService.getUserCityIds(userId);

            // If user has city restrictions, validate access
            if (userCityIds != null && !userCityIds.isEmpty()) {
                if (!userCityIds.contains(cityId.longValue())) {
                    logger.warn("🚫 Usuario ID {} intentó acceder a ciudad {} sin permiso. Ciudades permitidas: {}",
                        userId, cityId, userCityIds);

                    Map<String, Object> error = new HashMap<>();
                    error.put("error", "Acceso denegado");
                    error.put("message", "No tienes permiso para acceder a esta ciudad");
                    error.put("cityId", cityId);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
                }

                logger.debug("✅ Usuario ID {} tiene acceso a ciudad {}", userId, cityId);
            } else {
                logger.debug("Usuario ID {} no tiene restricciones de ciudad (puede ver todas)", userId);
            }

            // Get starting points from Glovo API (cached 30 days)
            List<StartingPointDto> startingPoints = cityService.getStartingPoints(tenantId, cityId);

            logger.info("Tenant {}, User {}: Returning {} starting points for city {}",
                tenantId, userId, startingPoints.size(), cityId);

            return ResponseEntity.ok(startingPoints);

        } catch (Exception e) {
            logger.error("Tenant {}, User {}: Error fetching starting points for city {}: {}",
                tenantId, userId, cityId, e.getMessage(), e);

            Map<String, Object> error = new HashMap<>();
            error.put("error", "Error obteniendo starting points");
            error.put("message", e.getMessage());
            error.put("cityId", cityId);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
