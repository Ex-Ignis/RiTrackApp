package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.StartingPointDto;
import es.hargos.ritrack.service.CityService;
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
     * Cache: 30 days TTL (starting points rarely change)
     * Multi-tenant: Uses TenantContext (ThreadLocal)
     */
    @GetMapping("/{cityId}/starting-points")
    public ResponseEntity<?> getStartingPoints(@PathVariable Integer cityId) {
        logger.info("Received request for starting points: cityId={}", cityId);

        // Extract tenant from context
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? tenantInfo.getFirstTenantId() : null;

        if (tenantId == null) {
            logger.warn("Tenant ID not found in context");
            Map<String, Object> error = new HashMap<>();
            error.put("error", "No se pudo determinar el tenant");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            // Get starting points from Glovo API (cached 30 days)
            List<StartingPointDto> startingPoints = cityService.getStartingPoints(tenantId, cityId);

            logger.info("Tenant {}: Returning {} starting points for city {}",
                tenantId, startingPoints.size(), cityId);

            return ResponseEntity.ok(startingPoints);

        } catch (Exception e) {
            logger.error("Tenant {}: Error fetching starting points for city {}: {}",
                tenantId, cityId, e.getMessage(), e);

            Map<String, Object> error = new HashMap<>();
            error.put("error", "Error obteniendo starting points");
            error.put("message", e.getMessage());
            error.put("cityId", cityId);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
