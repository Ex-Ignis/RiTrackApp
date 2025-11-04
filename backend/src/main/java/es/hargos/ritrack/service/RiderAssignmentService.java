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

    @Autowired
    public RiderAssignmentService(GlovoClient glovoClient, RoosterCacheService roosterCache) {
        this.glovoClient = glovoClient;
        this.roosterCache = roosterCache;
    }

    /**
     * Actualiza los starting points de un rider
     */
    public Map<String, Object> updateStartingPoints(Long tenantId, Integer riderId, List<Integer> startingPointIds) throws Exception {
        logger.info("Tenant {}: Actualizando starting points para rider {}: {}", tenantId, riderId, startingPointIds);

        // Verificar que el rider existe
        Object rider = glovoClient.obtenerEmpleadoPorId(tenantId, riderId);
        if (rider == null) {
            throw new RuntimeException("Tenant " + tenantId + ": Rider no encontrado: " + riderId);
        }

        // Preparar payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("starting_point_ids", startingPointIds);

        // Llamar a la API
        Object result = glovoClient.asignarPuntosInicioAEmpleado(tenantId, riderId, payload);

        // Limpiar caché para reflejar cambios
        roosterCache.clearCache();
        logger.info("Tenant {}: Starting points actualizados y caché limpiado para rider {}", tenantId, riderId);

        return (Map<String, Object>) result;
    }

    /**
     * Actualiza los vehículos asignados a un rider
     */
    public Map<String, Object> updateVehicles(Long tenantId, Integer riderId, List<Integer> vehicleTypeIds) throws Exception {
        logger.info("Tenant {}: Actualizando vehículos para rider {}: {}", tenantId, riderId, vehicleTypeIds);

        // Verificar que el rider existe
        Object rider = glovoClient.obtenerEmpleadoPorId(tenantId, riderId);
        if (rider == null) {
            throw new RuntimeException("Tenant " + tenantId + ": Rider no encontrado: " + riderId);
        }

        // Preparar payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("vehicle_type_ids", vehicleTypeIds);

        // Llamar a la API
        Object result = glovoClient.asignarVehiculosAEmpleado(tenantId, riderId, payload);

        // Limpiar caché para reflejar cambios
        roosterCache.clearCache();
        logger.info("Tenant {}: Vehículos actualizados y caché limpiado para rider {}", tenantId, riderId);

        return (Map<String, Object>) result;
    }
}