package es.hargos.ritrack.controller;

import es.hargos.ritrack.entity.TenantEntity;
import es.hargos.ritrack.entity.TenantSettingsEntity;
import es.hargos.ritrack.entity.RiderLimitWarningEntity;
import es.hargos.ritrack.repository.TenantRepository;
import es.hargos.ritrack.repository.TenantSettingsRepository;
import es.hargos.ritrack.repository.RiderLimitWarningRepository;
import es.hargos.ritrack.service.RiderLimitService;
import es.hargos.ritrack.service.TenantSchemaService;
import es.hargos.ritrack.context.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller interno para comunicación entre HargosAuth y RiTrack.
 * Usado solo para comunicación interna entre servicios.
 *
 * Completamente dinámico - devuelve todos los settings sin hardcodear.
 */
@RestController
@RequestMapping("/internal/admin")
public class InternalAdminController {

    private static final Logger logger = LoggerFactory.getLogger(InternalAdminController.class);

    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository settingsRepository;
    private final RiderLimitWarningRepository warningRepository;
    private final RiderLimitService riderLimitService;
    private final TenantSchemaService tenantSchemaService;

    @PersistenceContext
    private EntityManager entityManager;

    public InternalAdminController(TenantRepository tenantRepository,
                                   TenantSettingsRepository settingsRepository,
                                   RiderLimitWarningRepository warningRepository,
                                   RiderLimitService riderLimitService,
                                   TenantSchemaService tenantSchemaService) {
        this.tenantRepository = tenantRepository;
        this.settingsRepository = settingsRepository;
        this.warningRepository = warningRepository;
        this.riderLimitService = riderLimitService;
        this.tenantSchemaService = tenantSchemaService;
    }

    /**
     * Obtiene TODA la configuración de un tenant por su hargosTenantId.
     * Devuelve todos los settings dinámicamente desde la base de datos.
     *
     * GET /internal/admin/tenants/{hargosTenantId}/config
     */
    @GetMapping("/tenants/{hargosTenantId}/config")
    public ResponseEntity<?> getTenantConfig(@PathVariable Long hargosTenantId) {
        try {
            // Buscar tenant por hargosTenantId
            TenantEntity tenant = tenantRepository.findByHargosTenantId(hargosTenantId)
                    .orElse(null);

            if (tenant == null) {
                logger.info("Tenant con hargosTenantId {} no existe en RiTrack", hargosTenantId);
                return ResponseEntity.ok(Map.of(
                        "found", false,
                        "hargosTenantId", hargosTenantId
                ));
            }

            Long ritrackTenantId = tenant.getId();
            logger.info("Obteniendo config para hargosTenantId: {} (ritrackId: {})",
                    hargosTenantId, ritrackTenantId);

            // Construir respuesta con datos básicos del tenant
            Map<String, Object> response = new HashMap<>();
            response.put("found", true);
            response.put("hargosTenantId", hargosTenantId);
            response.put("ritrackTenantId", ritrackTenantId);
            response.put("tenantName", tenant.getName());
            response.put("schemaName", tenant.getSchemaName());
            response.put("isActive", tenant.getIsActive());

            // Obtener TODOS los settings dinámicamente
            List<TenantSettingsEntity> allSettings = settingsRepository.findByTenantId(ritrackTenantId);
            Map<String, Object> settings = new HashMap<>();

            for (TenantSettingsEntity setting : allSettings) {
                String key = setting.getSettingKey();
                String value = setting.getSettingValue();
                String type = setting.getSettingType();

                // Convertir el valor según su tipo
                Object parsedValue = parseSettingValue(value, type);
                settings.put(key, parsedValue);
            }

            response.put("settings", settings);

            // Intentar obtener conteo de riders (puede fallar si no hay credenciales)
            try {
                Integer riderCount = riderLimitService.countRidersInGlovo(ritrackTenantId);
                response.put("currentRiderCount", riderCount);
            } catch (Exception e) {
                logger.debug("No se pudo obtener conteo de riders: {}", e.getMessage());
                response.put("currentRiderCount", null);
            }

            logger.info("Config obtenida para hargosTenantId {}: {} settings",
                    hargosTenantId, settings.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error obteniendo config para hargosTenantId {}: {}",
                    hargosTenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno"));
        }
    }

    /**
     * Actualiza settings de un tenant por su hargosTenantId.
     * Solo actualiza los settings proporcionados en el request.
     *
     * PUT /internal/admin/tenants/{hargosTenantId}/settings
     * Body: Map<String, Object> con los settings a actualizar
     */
    @PutMapping("/tenants/{hargosTenantId}/settings")
    public ResponseEntity<?> updateTenantSettings(
            @PathVariable Long hargosTenantId,
            @RequestBody Map<String, Object> settingsToUpdate) {
        try {
            // Buscar tenant por hargosTenantId
            TenantEntity tenant = tenantRepository.findByHargosTenantId(hargosTenantId)
                    .orElse(null);

            if (tenant == null) {
                logger.warn("Tenant con hargosTenantId {} no existe en RiTrack", hargosTenantId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Tenant no encontrado"));
            }

            Long ritrackTenantId = tenant.getId();
            logger.info("Actualizando settings para hargosTenantId: {} (ritrackId: {}), settings: {}",
                    hargosTenantId, ritrackTenantId, settingsToUpdate.keySet());

            int updated = 0;
            for (Map.Entry<String, Object> entry : settingsToUpdate.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Buscar setting existente
                TenantSettingsEntity setting = settingsRepository
                        .findByTenantIdAndSettingKey(ritrackTenantId, key)
                        .orElse(null);

                if (setting != null) {
                    // Actualizar valor existente
                    setting.setSettingValue(convertToString(value));
                    settingsRepository.save(setting);
                    updated++;
                    logger.debug("Setting '{}' actualizado para tenant {}", key, ritrackTenantId);
                } else {
                    // Crear nuevo setting usando Builder
                    TenantSettingsEntity newSetting = TenantSettingsEntity.builder()
                            .tenant(tenant)
                            .settingKey(key)
                            .settingValue(convertToString(value))
                            .settingType(inferType(value))
                            .build();
                    settingsRepository.save(newSetting);
                    updated++;
                    logger.debug("Setting '{}' creado para tenant {}", key, ritrackTenantId);
                }
            }

            logger.info("Actualizados {} settings para hargosTenantId {}", updated, hargosTenantId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "hargosTenantId", hargosTenantId,
                    "updatedSettings", updated
            ));

        } catch (Exception e) {
            logger.error("Error actualizando settings para hargosTenantId {}: {}",
                    hargosTenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno"));
        }
    }

    /**
     * Convierte un valor a String para guardar en la base de datos.
     */
    private String convertToString(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        return String.valueOf(value);
    }

    /**
     * Infiere el tipo de un valor para guardarlo correctamente.
     */
    private String inferType(Object value) {
        if (value == null) return "STRING";
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Number) return "NUMBER";
        return "STRING";
    }

    /**
     * Parsea una fecha/hora desde múltiples formatos posibles.
     * Soporta: yyyy-MM-dd, yyyy-MM-ddTHH:mm:ss, yyyy-MM-dd HH:mm:ss.S, etc.
     */
    private LocalDateTime parseDateTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }

        // Formato date-only: 2025-12-15
        if (dateStr.length() == 10) {
            return java.time.LocalDate.parse(dateStr).atStartOfDay();
        }

        // Formato ISO: 2025-12-15T10:30:00
        if (dateStr.contains("T")) {
            return LocalDateTime.parse(dateStr);
        }

        // Formato SQL timestamp: 2025-12-10 00:00:00.0
        if (dateStr.contains(" ")) {
            // Normalizar el formato removiendo fracciones de segundo si existen
            String normalized = dateStr;
            if (normalized.contains(".")) {
                normalized = normalized.substring(0, normalized.indexOf("."));
            }
            java.time.format.DateTimeFormatter formatter =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(normalized, formatter);
        }

        // Fallback: intentar ISO por defecto
        return LocalDateTime.parse(dateStr);
    }

    /**
     * Parsea el valor del setting según su tipo declarado.
     */
    private Object parseSettingValue(String value, String type) {
        if (value == null) return null;
        if (type == null) type = "STRING";

        try {
            switch (type.toUpperCase()) {
                case "NUMBER":
                    if (value.contains(".")) {
                        return Double.parseDouble(value);
                    }
                    return Long.parseLong(value);

                case "BOOLEAN":
                    return Boolean.parseBoolean(value);

                case "JSON":
                    // Para JSON, devolver como string - el frontend puede parsearlo
                    return value;

                default: // STRING
                    return value;
            }
        } catch (Exception e) {
            // Si falla el parseo, devolver como string
            return value;
        }
    }

    // ==================== RIDER LIMIT WARNINGS CRUD ====================

    /**
     * Configura el contexto del tenant para operaciones multi-tenant.
     */
    private void setTenantContext(TenantEntity tenant) {
        TenantContext.TenantInfo tenantInfo = TenantContext.TenantInfo.builder()
                .tenantIds(List.of(tenant.getId()))
                .schemaNames(List.of(tenant.getSchemaName()))
                .selectedTenantId(tenant.getId())
                .build();
        TenantContext.setCurrentContext(tenantInfo);
    }

    /**
     * Obtiene todos los warnings de un tenant.
     *
     * GET /internal/admin/tenants/{hargosTenantId}/warnings
     */
    @GetMapping("/tenants/{hargosTenantId}/warnings")
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> getTenantWarnings(@PathVariable Long hargosTenantId) {
        try {
            TenantEntity tenant = tenantRepository.findByHargosTenantId(hargosTenantId)
                    .orElse(null);

            if (tenant == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Tenant no encontrado"));
            }

            String schema = tenant.getSchemaName();
            String sql = "SELECT id, current_count, allowed_limit, excess_count, expires_at, " +
                    "is_resolved, resolved_at, resolved_by, resolution_note, created_at, updated_at " +
                    "FROM " + schema + ".rider_limit_warnings ORDER BY created_at DESC";

            List<Object[]> results = entityManager.createNativeQuery(sql).getResultList();

            List<Map<String, Object>> warningList = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();

            for (Object[] row : results) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", row[0]);
                map.put("currentCount", row[1]);
                map.put("allowedLimit", row[2]);
                map.put("excessCount", row[3]);
                map.put("expiresAt", row[4] != null ? row[4].toString() : null);
                map.put("isResolved", row[5]);
                map.put("resolvedAt", row[6] != null ? row[6].toString() : null);
                map.put("resolvedBy", row[7]);
                map.put("resolutionNote", row[8]);
                map.put("createdAt", row[9] != null ? row[9].toString() : null);
                map.put("updatedAt", row[10] != null ? row[10].toString() : null);

                // Calcular isExpired e isActive
                java.sql.Timestamp expiresAtTs = (java.sql.Timestamp) row[4];
                Boolean isResolved = (Boolean) row[5];
                boolean isExpired = expiresAtTs != null && expiresAtTs.toLocalDateTime().isBefore(now);
                boolean isActive = !Boolean.TRUE.equals(isResolved) && !isExpired;
                map.put("isExpired", isExpired);
                map.put("isActive", isActive);

                warningList.add(map);
            }

            logger.info("Obtenidos {} warnings para hargosTenantId {}", warningList.size(), hargosTenantId);

            return ResponseEntity.ok(Map.of(
                    "hargosTenantId", hargosTenantId,
                    "warnings", warningList
            ));

        } catch (Exception e) {
            logger.error("Error obteniendo warnings para hargosTenantId {}: {}", hargosTenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno"));
        }
    }

    /**
     * Crea un nuevo warning para un tenant.
     *
     * POST /internal/admin/tenants/{hargosTenantId}/warnings
     */
    @PostMapping("/tenants/{hargosTenantId}/warnings")
    @Transactional
    public ResponseEntity<?> createWarning(
            @PathVariable Long hargosTenantId,
            @RequestBody Map<String, Object> warningData) {
        try {
            TenantEntity tenant = tenantRepository.findByHargosTenantId(hargosTenantId)
                    .orElse(null);

            if (tenant == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Tenant no encontrado"));
            }

            Integer currentCount = (Integer) warningData.get("currentCount");
            Integer allowedLimit = (Integer) warningData.get("allowedLimit");
            Integer excessCount = (Integer) warningData.get("excessCount");
            String expiresAtStr = (String) warningData.get("expiresAt");

            if (currentCount == null || allowedLimit == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "currentCount y allowedLimit son requeridos"));
            }

            if (excessCount == null) {
                excessCount = currentCount - allowedLimit;
            }

            LocalDateTime expiresAt;
            if (expiresAtStr != null && !expiresAtStr.isEmpty()) {
                if (expiresAtStr.length() == 10) {
                    expiresAt = java.time.LocalDate.parse(expiresAtStr).atStartOfDay();
                } else {
                    expiresAt = LocalDateTime.parse(expiresAtStr);
                }
            } else {
                expiresAt = LocalDateTime.now().plusDays(7);
            }

            String schema = tenant.getSchemaName();
            LocalDateTime now = LocalDateTime.now();

            String sql = "INSERT INTO " + schema + ".rider_limit_warnings " +
                    "(current_count, allowed_limit, excess_count, expires_at, is_resolved, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, false, ?, ?) RETURNING id";

            Object result = entityManager.createNativeQuery(sql)
                    .setParameter(1, currentCount)
                    .setParameter(2, allowedLimit)
                    .setParameter(3, excessCount)
                    .setParameter(4, expiresAt)
                    .setParameter(5, now)
                    .setParameter(6, now)
                    .getSingleResult();

            Long warningId = ((Number) result).longValue();

            logger.info("Warning creado para hargosTenantId {}: id={}", hargosTenantId, warningId);

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "success", true,
                    "id", warningId,
                    "message", "Warning creado correctamente"
            ));

        } catch (Exception e) {
            logger.error("Error creando warning para hargosTenantId {}: {}", hargosTenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno: " + e.getMessage()));
        }
    }

    /**
     * Actualiza un warning existente.
     *
     * PUT /internal/admin/tenants/{hargosTenantId}/warnings/{warningId}
     */
    @PutMapping("/tenants/{hargosTenantId}/warnings/{warningId}")
    @Transactional
    public ResponseEntity<?> updateWarning(
            @PathVariable Long hargosTenantId,
            @PathVariable Long warningId,
            @RequestBody Map<String, Object> warningData) {
        try {
            TenantEntity tenant = tenantRepository.findByHargosTenantId(hargosTenantId)
                    .orElse(null);

            if (tenant == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Tenant no encontrado"));
            }

            String schema = tenant.getSchemaName();

            // Verificar que existe
            String checkSql = "SELECT id FROM " + schema + ".rider_limit_warnings WHERE id = ?";
            List<?> existing = entityManager.createNativeQuery(checkSql)
                    .setParameter(1, warningId)
                    .getResultList();

            if (existing.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Warning no encontrado"));
            }

            // Construir UPDATE dinámico
            StringBuilder updateSql = new StringBuilder("UPDATE " + schema + ".rider_limit_warnings SET updated_at = ?");
            List<Object> params = new ArrayList<>();
            params.add(LocalDateTime.now());
            int paramIndex = 2;

            if (warningData.containsKey("currentCount")) {
                updateSql.append(", current_count = ?");
                params.add(warningData.get("currentCount"));
                paramIndex++;
            }
            if (warningData.containsKey("allowedLimit")) {
                updateSql.append(", allowed_limit = ?");
                params.add(warningData.get("allowedLimit"));
                paramIndex++;
            }
            if (warningData.containsKey("excessCount")) {
                updateSql.append(", excess_count = ?");
                params.add(warningData.get("excessCount"));
                paramIndex++;
            }
            if (warningData.containsKey("expiresAt")) {
                String expiresAtStr = (String) warningData.get("expiresAt");
                if (expiresAtStr != null && !expiresAtStr.isEmpty()) {
                    LocalDateTime expiresAt = parseDateTime(expiresAtStr);
                    updateSql.append(", expires_at = ?");
                    params.add(expiresAt);
                    paramIndex++;
                }
            }
            if (warningData.containsKey("isResolved")) {
                Boolean isResolved = (Boolean) warningData.get("isResolved");
                updateSql.append(", is_resolved = ?");
                params.add(isResolved);
                paramIndex++;

                if (Boolean.TRUE.equals(isResolved)) {
                    updateSql.append(", resolved_at = ?");
                    params.add(LocalDateTime.now());
                    paramIndex++;

                    if (warningData.containsKey("resolvedBy")) {
                        updateSql.append(", resolved_by = ?");
                        params.add(warningData.get("resolvedBy"));
                        paramIndex++;
                    }
                } else {
                    updateSql.append(", resolved_at = NULL, resolved_by = NULL");
                }
            }
            if (warningData.containsKey("resolutionNote")) {
                updateSql.append(", resolution_note = ?");
                params.add(warningData.get("resolutionNote"));
                paramIndex++;
            }

            updateSql.append(" WHERE id = ?");
            params.add(warningId);

            var query = entityManager.createNativeQuery(updateSql.toString());
            for (int i = 0; i < params.size(); i++) {
                query.setParameter(i + 1, params.get(i));
            }
            query.executeUpdate();

            logger.info("Warning actualizado para hargosTenantId {}: id={}", hargosTenantId, warningId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "id", warningId,
                    "message", "Warning actualizado correctamente"
            ));

        } catch (Exception e) {
            logger.error("Error actualizando warning {} para hargosTenantId {}: {}",
                    warningId, hargosTenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno: " + e.getMessage()));
        }
    }

    /**
     * Elimina un warning.
     *
     * DELETE /internal/admin/tenants/{hargosTenantId}/warnings/{warningId}
     */
    @DeleteMapping("/tenants/{hargosTenantId}/warnings/{warningId}")
    @Transactional
    public ResponseEntity<?> deleteWarning(
            @PathVariable Long hargosTenantId,
            @PathVariable Long warningId) {
        try {
            TenantEntity tenant = tenantRepository.findByHargosTenantId(hargosTenantId)
                    .orElse(null);

            if (tenant == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Tenant no encontrado"));
            }

            String schema = tenant.getSchemaName();

            String deleteSql = "DELETE FROM " + schema + ".rider_limit_warnings WHERE id = ?";
            int deleted = entityManager.createNativeQuery(deleteSql)
                    .setParameter(1, warningId)
                    .executeUpdate();

            if (deleted == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Warning no encontrado"));
            }

            logger.info("Warning eliminado para hargosTenantId {}: id={}", hargosTenantId, warningId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Warning eliminado correctamente"
            ));

        } catch (Exception e) {
            logger.error("Error eliminando warning {} para hargosTenantId {}: {}",
                    warningId, hargosTenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno: " + e.getMessage()));
        }
    }

    // ==================== TENANT CREATION ====================

    /**
     * Crea un tenant en RiTrack con su schema de PostgreSQL.
     * Llamado desde HargosAuth al crear un tenant de tipo RiTrack.
     *
     * POST /internal/admin/tenants
     * Body: { hargosTenantId, name }
     */
    @PostMapping("/tenants")
    @Transactional
    public ResponseEntity<?> createTenant(@RequestBody Map<String, Object> requestBody) {
        try {
            Long hargosTenantId = ((Number) requestBody.get("hargosTenantId")).longValue();
            String name = (String) requestBody.get("name");

            if (hargosTenantId == null || name == null || name.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "hargosTenantId y name son requeridos"));
            }

            // Verificar si ya existe
            if (tenantRepository.findByHargosTenantId(hargosTenantId).isPresent()) {
                logger.warn("Tenant con hargosTenantId {} ya existe en RiTrack", hargosTenantId);
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "hargosTenantId", hargosTenantId,
                        "message", "Tenant ya existía en RiTrack"
                ));
            }

            // Generar nombre de schema (lowercase, sin espacios ni caracteres especiales)
            String schemaName = name.toLowerCase()
                    .replaceAll("[^a-z0-9_]", "")
                    .replaceAll("\\s+", "");

            // Verificar que el schema no exista
            if (tenantRepository.findBySchemaName(schemaName).isPresent()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Ya existe un tenant con schema: " + schemaName));
            }

            logger.info("Creando tenant en RiTrack: {} (hargosTenantId: {}, schema: {})",
                    name, hargosTenantId, schemaName);

            // 1. Crear el tenant en la tabla public.tenants
            TenantEntity tenant = TenantEntity.builder()
                    .hargosTenantId(hargosTenantId)
                    .name(name)
                    .schemaName(schemaName)
                    .isActive(false)  // Se activará después del onboarding con credenciales
                    .build();
            tenant = tenantRepository.save(tenant);
            logger.info("Tenant creado en public.tenants: id={}", tenant.getId());

            // 2. Crear el schema de PostgreSQL con todas las tablas
            tenantSchemaService.createSchema(schemaName);
            logger.info("Schema {} creado en PostgreSQL", schemaName);

            logger.info("Tenant {} creado completamente en RiTrack (pendiente onboarding)", hargosTenantId);

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "success", true,
                    "hargosTenantId", hargosTenantId,
                    "ritrackTenantId", tenant.getId(),
                    "schemaName", schemaName,
                    "isActive", false,
                    "message", "Tenant y schema creados. Pendiente configurar credenciales Glovo."
            ));

        } catch (Exception e) {
            logger.error("Error creando tenant: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error creando tenant: " + e.getMessage()));
        }
    }

    // ==================== TENANT DELETION ====================

    /**
     * Elimina un tenant de RiTrack y su schema de la base de datos.
     * Esta operación es DESTRUCTIVA e IRREVERSIBLE.
     *
     * DELETE /internal/admin/tenants/{hargosTenantId}
     */
    @DeleteMapping("/tenants/{hargosTenantId}")
    @Transactional
    public ResponseEntity<?> deleteTenant(@PathVariable Long hargosTenantId) {
        try {
            TenantEntity tenant = tenantRepository.findByHargosTenantId(hargosTenantId)
                    .orElse(null);

            if (tenant == null) {
                logger.info("Tenant con hargosTenantId {} no existe en RiTrack, nada que eliminar", hargosTenantId);
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "hargosTenantId", hargosTenantId,
                        "message", "Tenant no existía en RiTrack"
                ));
            }

            String schemaName = tenant.getSchemaName();
            Long ritrackTenantId = tenant.getId();

            logger.warn("ELIMINANDO tenant {} (hargosTenantId: {}, schema: {})",
                    tenant.getName(), hargosTenantId, schemaName);

            // 1. Eliminar settings del tenant
            settingsRepository.deleteByTenantId(ritrackTenantId);
            logger.info("Settings eliminados para tenant {}", ritrackTenantId);

            // 2. Eliminar el tenant de la tabla tenants
            tenantRepository.delete(tenant);
            logger.info("Tenant eliminado de la tabla tenants: {}", ritrackTenantId);

            // 3. Eliminar el schema de PostgreSQL (DROP SCHEMA CASCADE)
            if (schemaName != null && !schemaName.isEmpty()) {
                tenantSchemaService.dropSchema(schemaName);
                logger.info("Schema {} eliminado de PostgreSQL", schemaName);
            }

            logger.info("Tenant {} eliminado completamente de RiTrack", hargosTenantId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "hargosTenantId", hargosTenantId,
                    "schemaDeleted", schemaName,
                    "message", "Tenant y schema eliminados correctamente"
            ));

        } catch (Exception e) {
            logger.error("Error eliminando tenant {}: {}", hargosTenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error eliminando tenant: " + e.getMessage()));
        }
    }
}
