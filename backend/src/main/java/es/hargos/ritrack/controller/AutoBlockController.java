package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.AutoBlockConfigDto;
import es.hargos.ritrack.dto.RiderBlockStatusDto;
import es.hargos.ritrack.entity.RiderBlockStatusEntity;
import es.hargos.ritrack.service.AutoBlockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Controller para gestión de auto-bloqueo de riders por saldo de cash alto.
 *
 * Endpoints:
 * - GET  /api/v1/auto-block/config - Ver configuración
 * - PUT  /api/v1/auto-block/config - Actualizar configuración
 * - GET  /api/v1/auto-block/status - Riders auto-bloqueados actualmente
 * - GET  /api/v1/auto-block/status/{employeeId} - Estado de un rider específico
 */
@RestController
@RequestMapping("/api/v1/auto-block")
@CrossOrigin(origins = "*")
public class AutoBlockController {

    private static final Logger logger = LoggerFactory.getLogger(AutoBlockController.class);
    private final AutoBlockService autoBlockService;

    @Autowired
    public AutoBlockController(AutoBlockService autoBlockService) {
        this.autoBlockService = autoBlockService;
    }

    /**
     * Obtiene la configuración actual de auto-bloqueo del tenant.
     *
     * GET /api/v1/auto-block/config
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
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
            AutoBlockConfigDto config = autoBlockService.getAutoBlockConfig(tenantId);
            return ResponseEntity.ok(config);

        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo configuración de auto-bloqueo: {}",
                    tenantId, e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error obteniendo configuración");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Actualiza la configuración de auto-bloqueo del tenant.
     *
     * PUT /api/v1/auto-block/config
     *
     * Body:
     * {
     *   "enabled": true,
     *   "cashLimit": 150.00
     * }
     */
    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody AutoBlockConfigDto request) {
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
            // Validaciones
            if (request.getEnabled() == null || request.getCashLimit() == null) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Campos requeridos faltantes");
                error.put("message", "enabled y cashLimit son requeridos");
                return ResponseEntity.badRequest().body(error);
            }

            if (request.getCashLimit().doubleValue() <= 0) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Límite inválido");
                error.put("message", "cashLimit debe ser mayor a 0");
                return ResponseEntity.badRequest().body(error);
            }

            AutoBlockConfigDto updatedConfig = autoBlockService.updateAutoBlockConfig(tenantId, request);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Configuración actualizada correctamente");
            response.put("config", updatedConfig);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Tenant {}: Error actualizando configuración de auto-bloqueo: {}",
                    tenantId, e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error actualizando configuración");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Obtiene la lista de riders actualmente auto-bloqueados.
     *
     * GET /api/v1/auto-block/status
     */
    @GetMapping("/status")
    public ResponseEntity<?> getCurrentlyBlocked() {
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
            List<RiderBlockStatusEntity> blockedRiders = autoBlockService.getCurrentlyAutoBlocked();

            List<RiderBlockStatusDto> dtos = blockedRiders.stream()
                    .map(RiderBlockStatusDto::fromEntity)
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("count", dtos.size());
            response.put("riders", dtos);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo riders auto-bloqueados: {}",
                    tenantId, e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error obteniendo riders bloqueados");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Obtiene el estado de bloqueo de un rider específico.
     *
     * GET /api/v1/auto-block/status/{employeeId}
     */
    @GetMapping("/status/{employeeId}")
    public ResponseEntity<?> getRiderStatus(@PathVariable String employeeId) {
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
            Optional<RiderBlockStatusEntity> statusOpt = autoBlockService.getRiderBlockStatus(employeeId);

            if (statusOpt.isEmpty()) {
                Map<String, String> response = new HashMap<>();
                response.put("employeeId", employeeId);
                response.put("message", "No se encontró información de bloqueo para este rider");
                return ResponseEntity.ok(response);
            }

            RiderBlockStatusDto dto = RiderBlockStatusDto.fromEntity(statusOpt.get());
            return ResponseEntity.ok(dto);

        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo estado de rider {}: {}",
                    tenantId, employeeId, e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error obteniendo estado de rider");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
