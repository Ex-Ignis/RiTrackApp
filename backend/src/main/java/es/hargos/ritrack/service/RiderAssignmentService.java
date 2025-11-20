package es.hargos.ritrack.service;

import es.hargos.ritrack.client.GlovoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RiderAssignmentService {

    private static final Logger logger = LoggerFactory.getLogger(RiderAssignmentService.class);

    private final GlovoClient glovoClient;
    private final RoosterCacheService roosterCache;
    private final RiderDetailService riderDetailService;
    private final CityService cityService;
    private final AutoBlockService autoBlockService;

    @Autowired
    public RiderAssignmentService(GlovoClient glovoClient,
                                   RoosterCacheService roosterCache,
                                   RiderDetailService riderDetailService,
                                   CityService cityService,
                                   AutoBlockService autoBlockService) {
        this.glovoClient = glovoClient;
        this.roosterCache = roosterCache;
        this.riderDetailService = riderDetailService;
        this.cityService = cityService;
        this.autoBlockService = autoBlockService;
    }

    /**
     * Actualiza los starting points de un rider
     */
    public Map<String, Object> updateStartingPoints(Long tenantId, Integer riderId, List<Integer> startingPointIds) throws Exception {
        logger.info("Tenant {}: Actualizando starting points para rider {}: {}", tenantId, riderId, startingPointIds);

        // Verificar que el rider existe
        Object rider = glovoClient.getEmployeeById(tenantId, riderId);
        if (rider == null) {
            throw new RuntimeException("Tenant " + tenantId + ": Rider no encontrado: " + riderId);
        }

        // Preparar payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("starting_point_ids", startingPointIds);

        // Llamar a la API
        Object result = glovoClient.assignStartingPointsToEmployee(tenantId, riderId, payload);

        // Limpiar caché para reflejar cambios
        roosterCache.clearCache(tenantId);
        logger.info("Tenant {}: Starting points actualizados y caché limpiado para rider {}", tenantId, riderId);

        return (Map<String, Object>) result;
    }

    /**
     * Actualiza los vehículos asignados a un rider
     */
    public Map<String, Object> updateVehicles(Long tenantId, Integer riderId, List<Integer> vehicleTypeIds) throws Exception {
        logger.info("Tenant {}: Actualizando vehículos para rider {}: {}", tenantId, riderId, vehicleTypeIds);

        // Verificar que el rider existe
        Object rider = glovoClient.getEmployeeById(tenantId, riderId);
        if (rider == null) {
            throw new RuntimeException("Tenant " + tenantId + ": Rider no encontrado: " + riderId);
        }

        // Preparar payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("vehicle_type_ids", vehicleTypeIds);

        // Llamar a la API
        Object result = glovoClient.assignVehiclesToEmployee(tenantId, riderId, payload);

        // Limpiar caché para reflejar cambios
        roosterCache.clearCache(tenantId);
        logger.info("Tenant {}: Vehículos actualizados y caché limpiado para rider {}", tenantId, riderId);

        return (Map<String, Object>) result;
    }

    /**
     * Bloquea a un rider removiendo todos sus starting points
     *
     * @param tenantId ID del tenant
     * @param riderId ID del rider
     * @return Resultado de la operación
     * @throws Exception si ocurre algún error
     */
    public Map<String, Object> blockRider(Long tenantId, Integer riderId) throws Exception {
        logger.info("Tenant {}: Bloqueando rider {} (removiendo starting points)", tenantId, riderId);

        // Verificar que el rider existe
        Object rider = glovoClient.getEmployeeById(tenantId, riderId);
        if (rider == null) {
            throw new RuntimeException("Tenant " + tenantId + ": Rider no encontrado: " + riderId);
        }

        // Preparar payload con array vacío para bloquear
        Map<String, Object> payload = new HashMap<>();
        payload.put("starting_point_ids", List.of());

        // Llamar a la API
        Object result = glovoClient.assignStartingPointsToEmployee(tenantId, riderId, payload);

        // Marcar como bloqueo MANUAL (prioridad sobre auto-bloqueo)
        autoBlockService.markAsManualBlock(tenantId, riderId.toString(), true);

        // Limpiar caché para reflejar cambios
        roosterCache.clearCache(tenantId);
        logger.info("Tenant {}: Rider {} bloqueado exitosamente y caché limpiado", tenantId, riderId);

        return (Map<String, Object>) result;
    }

    /**
     * Desbloquea a un rider asignándole todos los starting points de su ciudad
     *
     * @param tenantId ID del tenant
     * @param riderId ID del rider
     * @return Resultado de la operación
     * @throws Exception si ocurre algún error
     */
    public Map<String, Object> unblockRider(Long tenantId, Integer riderId) throws Exception {
        logger.info("Tenant {}: Desbloqueando rider {} (asignando todos los starting points)", tenantId, riderId);

        // Obtener el cityId del rider desde su contrato activo
        Integer cityId = riderDetailService.getRiderCityId(tenantId, riderId);

        if (cityId == null) {
            throw new RuntimeException("Tenant " + tenantId + ": No se pudo obtener el cityId del rider " + riderId);
        }

        logger.info("Tenant {}: Rider {} tiene cityId: {}", tenantId, riderId, cityId);

        // Obtener todos los starting points de la ciudad desde Glovo API
        List<Integer> startingPointIds = cityService.getStartingPointIds(tenantId, cityId);

        logger.info("Tenant {}: Obtenidos {} starting points para ciudad {}", tenantId, startingPointIds.size(), cityId);

        // Preparar payload con todos los starting points
        Map<String, Object> payload = new HashMap<>();
        payload.put("starting_point_ids", startingPointIds);

        // Llamar a la API
        Object result = glovoClient.assignStartingPointsToEmployee(tenantId, riderId, payload);

        // Quitar marca de bloqueo MANUAL
        autoBlockService.markAsManualBlock(tenantId, riderId.toString(), false);

        // Limpiar caché para reflejar cambios
        roosterCache.clearCache(tenantId);
        logger.info("Tenant {}: Rider {} desbloqueado exitosamente con {} starting points y caché limpiado",
                tenantId, riderId, startingPointIds.size());

        return (Map<String, Object>) result;
    }
}