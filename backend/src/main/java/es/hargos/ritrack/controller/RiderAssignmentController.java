package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.service.RiderAssignmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/riders")
@CrossOrigin(origins = "*")
public class RiderAssignmentController {

    private static final Logger logger = LoggerFactory.getLogger(RiderAssignmentController.class);
    private final RiderAssignmentService assignmentService;

    @Autowired
    public RiderAssignmentController(RiderAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    /**
     * Asigna o actualiza starting points de un rider
     * PUT /api/v1/riders/{riderId}/starting-points
     */
    @PutMapping("/{riderId}/starting-points")
    public ResponseEntity<?> updateStartingPoints(
            @PathVariable Integer riderId,
            @RequestBody Map<String, List<Integer>> request) {

        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? tenantInfo.getFirstTenantId() : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            error.put("message", "No se pudo determinar el tenant del usuario");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            List<Integer> startingPointIds = request.get("starting_point_ids");

            if (startingPointIds == null) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "starting_point_ids es requerido");
                return ResponseEntity.badRequest().body(error);
            }

            Map<String, Object> result = assignmentService.updateStartingPoints(tenantId, riderId, startingPointIds);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Starting points actualizados correctamente");
            response.put("riderId", riderId);
            response.put("data", result);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error actualizando starting points para rider {}: {}", riderId, e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error actualizando starting points");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Asigna o actualiza vehículos de un rider
     * PUT /api/v1/riders/{riderId}/vehicles
     */
    @PutMapping("/{riderId}/vehicles")
    public ResponseEntity<?> updateVehicles(
            @PathVariable Integer riderId,
            @RequestBody Map<String, List<Integer>> request) {

        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? tenantInfo.getFirstTenantId() : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            error.put("message", "No se pudo determinar el tenant del usuario");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        try {
            List<Integer> vehicleTypeIds = request.get("vehicle_type_ids");

            if (vehicleTypeIds == null) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "vehicle_type_ids es requerido");
                return ResponseEntity.badRequest().body(error);
            }

            Map<String, Object> result = assignmentService.updateVehicles(tenantId, riderId, vehicleTypeIds);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Vehículos actualizados correctamente");
            response.put("riderId", riderId);
            response.put("data", result);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error actualizando vehículos para rider {}: {}", riderId, e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error actualizando vehículos");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
