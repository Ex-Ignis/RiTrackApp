package es.hargos.ritrack.controller;

import es.hargos.ritrack.service.DataSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/database")
public class DatabaseController {

    @Autowired
    private DataSyncService dataSyncService;

    @PostMapping("/initial-load")
    public ResponseEntity<?> initialDataLoad() {
        // Extraer tenantId del contexto
        es.hargos.ritrack.context.TenantContext.TenantInfo tenantInfo =
            es.hargos.ritrack.context.TenantContext.getCurrentContext();
        Long tenantId = tenantInfo != null ? tenantInfo.getFirstTenantId() : null;

        Map<String, Object> response = new HashMap<>();

        if (tenantId == null) {
            response.put("error", "Tenant no encontrado");
            response.put("status", "error");
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).body(response);
        }

        try {
            dataSyncService.initialDataLoad(tenantId);
            response.put("message", "Carga inicial ejecutada exitosamente para tenant " + tenantId);
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("message", "Error en carga inicial");
            response.put("error", e.getMessage());
            response.put("status", "error");
            return ResponseEntity.internalServerError().body(response);
        }
    }
}