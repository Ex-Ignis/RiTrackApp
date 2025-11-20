package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.RiderLocationDto;
import es.hargos.ritrack.service.RiderLocationService;
import es.hargos.ritrack.websocket.RiderLocationWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controlador para gestión de ubicaciones de riders en tiempo real
 *
 * NOTA: Para búsquedas y filtros de riders, usar RiderFilterController (/api/v1/riders/search)
 * Este controlador se enfoca únicamente en ubicaciones geográficas y tiempo real
 */
@RestController
@RequestMapping("/api/v1/rider-locations")
public class RiderLocationController {

    private static final Logger logger = LoggerFactory.getLogger(RiderLocationController.class);

    private final RiderLocationService riderLocationService;
    private final RiderLocationWebSocketHandler webSocketHandler;

    public RiderLocationController(RiderLocationService riderLocationService,
                                   RiderLocationWebSocketHandler webSocketHandler) {
        this.riderLocationService = riderLocationService;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * Obtiene ubicaciones actuales de todos los riders agrupadas por ciudad
     */
    @GetMapping("/current")
    public ResponseEntity<?> getCurrentLocations() {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            error.put("message", "No se pudo determinar el tenant del usuario");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            Map<Integer, List<RiderLocationDto>> locationsByCity =
                riderLocationService.getCurrentRiderLocationsByCity(tenantId);
            return ResponseEntity.ok(locationsByCity);
        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo ubicaciones actuales: {}", tenantId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Obtiene ubicaciones por ciudad específica
     */
    @GetMapping("/current/city/{cityId}")
    public ResponseEntity<?> getCurrentLocationsByCity(@PathVariable Integer cityId) {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            error.put("message", "No se pudo determinar el tenant del usuario");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            List<RiderLocationDto> locations = riderLocationService.getRiderLocationsByCity(tenantId, cityId);
            return ResponseEntity.ok(locations);
        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo ubicaciones de ciudad {}: {}", tenantId, cityId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Obtiene ubicaciones agrupadas por todas las ciudades
     */
    @GetMapping("/current/by-cities")
    public ResponseEntity<?> getCurrentLocationsByAllCities() {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            error.put("message", "No se pudo determinar el tenant del usuario");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            Map<Integer, List<RiderLocationDto>> locationsByCity =
                    riderLocationService.getCurrentRiderLocationsByCity(tenantId);
            return ResponseEntity.ok(locationsByCity);
        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo ubicaciones por ciudades: {}", tenantId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Obtiene riders con entregas activas
     */
    @GetMapping("/active-deliveries")
    public ResponseEntity<?> getRidersWithActiveDeliveries() {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            Map<Integer, List<RiderLocationDto>> locationsByCity =
                riderLocationService.getCurrentRiderLocationsByCity(tenantId);

            List<RiderLocationDto> activeDeliveries = locationsByCity.values().stream()
                    .flatMap(List::stream)
                    .filter(RiderLocationDto::getHasActiveDelivery)
                    .toList();

            return ResponseEntity.ok(activeDeliveries);
        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo riders con entregas activas: {}", tenantId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Obtiene riders con entregas activas por ciudad
     */
    @GetMapping("/city/{cityId}/active-deliveries")
    public ResponseEntity<?> getRidersWithActiveDeliveriesByCity(@PathVariable Integer cityId) {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            List<RiderLocationDto> cityLocations = riderLocationService.getRiderLocationsByCity(tenantId, cityId);
            List<RiderLocationDto> activeDeliveries = cityLocations.stream()
                    .filter(RiderLocationDto::getHasActiveDelivery)
                    .toList();

            return ResponseEntity.ok(activeDeliveries);
        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo entregas activas de ciudad {}: {}", tenantId, cityId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Obtiene riders por estado específico
     */
    @GetMapping("/by-status/{status}")
    public ResponseEntity<?> getLocationsByStatus(@PathVariable String status) {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            Map<Integer, List<RiderLocationDto>> locationsByCity =
                riderLocationService.getCurrentRiderLocationsByCity(tenantId);

            List<RiderLocationDto> filteredLocations = locationsByCity.values().stream()
                    .flatMap(List::stream)
                    .filter(location -> status.equalsIgnoreCase(location.getStatus()))
                    .toList();

            return ResponseEntity.ok(filteredLocations);
        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo riders por estado {}: {}", tenantId, status, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Obtiene riders por estado en ciudad específica
     */
    @GetMapping("/city/{cityId}/by-status/{status}")
    public ResponseEntity<?> getLocationsByStatusAndCity(
            @PathVariable Integer cityId, @PathVariable String status) {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            List<RiderLocationDto> cityLocations = riderLocationService.getRiderLocationsByCity(tenantId, cityId);
            List<RiderLocationDto> filteredLocations = cityLocations.stream()
                    .filter(location -> status.equalsIgnoreCase(location.getStatus()))
                    .toList();

            return ResponseEntity.ok(filteredLocations);
        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo riders por estado {} en ciudad {}: {}", tenantId, status, cityId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Fuerza actualización de ubicaciones para todas las ciudades de todos los tenants
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> forceRefresh() {
        try {
            riderLocationService.updateAllTenantsLocations();

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Actualización de ubicaciones iniciada para todos los tenants");
            response.put("timestamp", System.currentTimeMillis());
            response.put("active_connections", webSocketHandler.getActiveSessionsCount());
            response.put("connection_stats", webSocketHandler.getConnectionStats());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error al forzar actualización: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Fuerza actualización para ciudad específica
     * TODO: Implementar método forceRefreshCity en RiderLocationService
     */
    /*
    @PostMapping("/refresh/city/{cityId}")
    public ResponseEntity<Map<String, Object>> forceRefreshByCity(@PathVariable Integer cityId) {
        try {
            riderLocationService.forceRefreshCity(cityId);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Actualización iniciada para ciudad " + cityId);
            response.put("city_id", cityId);
            response.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error actualizando ciudad {}: {}", cityId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    */

    /**
     * Estadísticas generales del servicio
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            Map<Integer, List<RiderLocationDto>> locationsByCity =
                riderLocationService.getCurrentRiderLocationsByCity(tenantId);

            List<RiderLocationDto> allLocations = locationsByCity.values().stream()
                    .flatMap(List::stream)
                    .toList();

            Map<String, Object> stats = new HashMap<>();
            stats.put("total_riders", allLocations.size());
            stats.put("total_cities", locationsByCity.size());
            stats.put("active_websocket_connections", webSocketHandler.getActiveSessionsCount());
            stats.put("connection_stats", webSocketHandler.getConnectionStats());
            stats.put("riders_with_active_delivery",
                    allLocations.stream().mapToLong(r -> r.getHasActiveDelivery() ? 1 : 0).sum());
            stats.put("riders_working",
                    allLocations.stream().filter(r -> !"not_working".equals(r.getStatus())).count());
            stats.put("last_check", System.currentTimeMillis());

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo estadísticas: {}", tenantId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Estadísticas por ciudad específica
     * TODO: Implementar método getStatsByCity en RiderLocationService
     */
    /*
    @GetMapping("/stats/city/{cityId}")
    public ResponseEntity<Map<String, Object>> getStatsByCity(@PathVariable Integer cityId) {
        try {
            Map<String, Object> stats = riderLocationService.getStatsByCity(cityId);
            stats.put("websocket_stats", webSocketHandler.getConnectionStats());
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            logger.error("Error obteniendo estadísticas de ciudad {}: {}", cityId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    */

    /**
     * ENDPOINTS DE TESTING - Solo para desarrollo
     */

    /**
     * Envía datos de prueba via WebSocket
     * MULTI-TENANT: Requiere tenantId del contexto autenticado
     */
    @PostMapping("/test/broadcast")
    public ResponseEntity<Map<String, Object>> testBroadcast(
            @RequestParam Integer cityId,
            @RequestParam(defaultValue = "5") Integer count) {
        try {
            // MULTI-TENANT: Obtener tenantId del contexto
            TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
            Long tenantId = tenantInfo != null ? (tenantInfo.getSelectedTenantId() != null ? tenantInfo.getSelectedTenantId() : tenantInfo.getFirstTenantId()) : null;

            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Tenant ID no encontrado en contexto"));
            }

            List<RiderLocationDto> testData = generateTestRiderData(count, cityId);
            webSocketHandler.broadcastRiderLocationsByCity(tenantId, cityId, testData);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Datos de prueba enviados");
            response.put("tenant_id", tenantId);
            response.put("city_id", cityId);
            response.put("riders_sent", count);
            response.put("active_connections", webSocketHandler.getActiveSessionsCount());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error enviando datos de prueba: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Información sobre la diferencia entre controladores
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getControllerInfo() {
        Map<String, Object> info = new HashMap<>();

        info.put("purpose", "Gestión de ubicaciones geográficas en tiempo real");
        info.put("websocket_endpoint", "/ws/rider-locations");

        Map<String, String> recommendations = new HashMap<>();
        recommendations.put("for_location_data", "Usar este controlador (/api/v1/rider-locations/*)");
        recommendations.put("for_search_filters", "Usar RiderFilterController (/api/v1/riders/search)");
        recommendations.put("for_rider_details", "Usar RiderFilterController (/api/v1/riders/search)");

        info.put("recommendations", recommendations);

        Map<String, String> mainEndpoints = new HashMap<>();
        mainEndpoints.put("current_locations", "GET /api/v1/rider-locations/current");
        mainEndpoints.put("by_city", "GET /api/v1/rider-locations/current/city/{cityId}");
        mainEndpoints.put("active_deliveries", "GET /api/v1/rider-locations/active-deliveries");
        mainEndpoints.put("by_status", "GET /api/v1/rider-locations/by-status/{status}");
        mainEndpoints.put("refresh", "POST /api/v1/rider-locations/refresh");
        mainEndpoints.put("stats", "GET /api/v1/rider-locations/stats");

        info.put("main_endpoints", mainEndpoints);

        return ResponseEntity.ok(info);
    }

    /**
     * Genera datos de prueba para testing
     */
    private int employeeIdCounter = 1000;

    private List<RiderLocationDto> generateTestRiderData(int count, Integer cityId) {
        List<RiderLocationDto> testData = new java.util.ArrayList<>();

        for (int i = 1; i <= count; i++) {
            RiderLocationDto rider = new RiderLocationDto(
                    String.valueOf(employeeIdCounter++),
                    "Test Rider " + i,
                    40.4168 + (Math.random() - 0.5) * 0.1, // Latitud cerca de Madrid
                    -3.7038 + (Math.random() - 0.5) * 0.1, // Longitud cerca de Madrid
                    Math.random() * 50 + 5, // Precisión entre 5 y 55 metros
                    LocalDateTime.now().toString(),
                    i % 2 == 0 ? "working" : "available",
                    i % 3 == 0, // 1 de cada 3 tiene entrega activa
                    i % 2 == 0 ? "Scooter" : "Bicycle"
            );
            testData.add(rider);
        }

        return testData;
    }
}