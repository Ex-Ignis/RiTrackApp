package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.RiderCreateDto;
import es.hargos.ritrack.service.RiderCreateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/riders")
@CrossOrigin(origins = "*")
public class RiderCreateController {

    private static final Logger logger = LoggerFactory.getLogger(RiderCreateController.class);

    private final RiderCreateService riderCreateService;

    @Autowired
    public RiderCreateController(RiderCreateService riderCreateService) {
        this.riderCreateService = riderCreateService;
    }

    /**
     * Crea un nuevo rider
     * POST /api/v1/riders/create
     */
    @PostMapping("/create")
    public ResponseEntity<?> createRider(@RequestBody RiderCreateDto createData) {
        // Extraer tenantId del contexto
        TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? tenantInfo.getFirstTenantId() : null;

        if (tenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no encontrado");
            error.put("message", "No se pudo determinar el tenant del usuario");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        logger.info("Tenant {}: Solicitud de creación de rider: {}", tenantId, createData.getName());

        try {
            Map<String, Object> result = riderCreateService.createRider(tenantId, createData);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Rider creado exitosamente");
            response.put("data", result);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            logger.error("Error de validación: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Datos inválidos");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            logger.error("Error creando rider: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error al crear rider");
            error.put("message", "Por favor revise los datos e intente nuevamente");
            error.put("details", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}