package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.PaginatedResponseDto;
import es.hargos.ritrack.dto.RiderFilterDto;
import es.hargos.ritrack.dto.RiderSummaryDto;
import es.hargos.ritrack.service.RiderFilterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controlador simplificado para búsqueda y filtrado de riders
 */
@RestController
@RequestMapping("/api/v1/riders")
public class RiderFilterController {

    private static final Logger logger = LoggerFactory.getLogger(RiderFilterController.class);
    private final RiderFilterService riderFilterService;

    public RiderFilterController(RiderFilterService riderFilterService) {
        this.riderFilterService = riderFilterService;
    }

    /**
     * ENDPOINT PRINCIPAL - Búsqueda de riders con todos los filtros posibles
     *
     * Filtros disponibles:
     * - name: nombre del rider (búsqueda parcial, case insensitive)
     * - riderId: ID específico del rider (employee_id)
     * - phone: teléfono del rider (búsqueda parcial)
     * - status: estado del rider (not_working, working, available, break, ready, starting, ending, late, temp_not_working)
     * - cityId: ID de la ciudad
     * - contractType: tipo de contrato (FULL_TIME, PART_TIME)
     * - hasActiveDelivery: si tiene delivery activo (true/false)
     * - isWorking: si está trabajando (true/false)
     * - companyId: ID de la empresa
     *
     * Ejemplos:
     * GET /api/v1/riders/search?name=Juan&page=0&size=10
     * GET /api/v1/riders/search?status=working&cityId=123
     * GET /api/v1/riders/search?contractType=FULL_TIME&isWorking=true
     * GET /api/v1/riders/search?riderId=1234
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchRiders(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer riderId,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer cityId,
            @RequestParam(required = false) String contractType,
            @RequestParam(required = false) Boolean hasActiveDelivery,
            @RequestParam(required = false) Boolean isWorking,
            @RequestParam(required = false) Integer companyId,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {

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
            RiderFilterDto filters = RiderFilterDto.builder()
                    .name(name)
                    .riderId(riderId)
                    .phone(phone)
                    .email(email)
                    .status(status)
                    .cityId(cityId)
                    .contractType(contractType)
                    .hasActiveDelivery(hasActiveDelivery)
                    .isWorking(isWorking)
                    .companyId(companyId)
                    .page(page)
                    .size(size)
                    .build();

            logger.info("Tenant {}: Búsqueda de riders: {}", tenantId, filters);

            PaginatedResponseDto<RiderSummaryDto> result = riderFilterService.searchRiders(tenantId, filters);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Error en búsqueda de riders: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * ENDPOINT ALTERNATIVO CON POST - Para filtros complejos en el body
     */
    @PostMapping("/search")
    public ResponseEntity<?> searchRidersPost(
            @RequestBody RiderFilterDto filters) {

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
            logger.info("Tenant {}: Búsqueda de riders (POST): {}", tenantId, filters);

            PaginatedResponseDto<RiderSummaryDto> result = riderFilterService.searchRiders(tenantId, filters);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Error en búsqueda de riders (POST): {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}