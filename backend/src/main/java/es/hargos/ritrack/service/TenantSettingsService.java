package es.hargos.ritrack.service;

import es.hargos.ritrack.dto.UpdateSettingsRequest;
import es.hargos.ritrack.entity.GlovoCredentialsEntity;
import es.hargos.ritrack.entity.TenantEntity;
import es.hargos.ritrack.entity.TenantSettingsEntity;
import es.hargos.ritrack.repository.GlovoCredentialsRepository;
import es.hargos.ritrack.repository.TenantRepository;
import es.hargos.ritrack.repository.TenantSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio para leer y actualizar configuraciones específicas de cada tenant desde la base de datos.
 * Reemplaza configuraciones hardcodeadas en application.properties.
 */
@Service
public class TenantSettingsService {

    private static final Logger logger = LoggerFactory.getLogger(TenantSettingsService.class);
    private final TenantSettingsRepository settingsRepository;
    private final TenantRepository tenantRepository;
    private final GlovoCredentialsRepository credentialsRepository;

    public TenantSettingsService(TenantSettingsRepository settingsRepository,
                                 TenantRepository tenantRepository,
                                 GlovoCredentialsRepository credentialsRepository) {
        this.settingsRepository = settingsRepository;
        this.tenantRepository = tenantRepository;
        this.credentialsRepository = credentialsRepository;
    }

    /**
     * Obtiene una configuración de tenant (cacheada)
     */
    @Cacheable(value = "tenant-settings", key = "#tenantId + '-' + #settingKey")
    public String getSetting(Long tenantId, String settingKey) {
        return settingsRepository.findByTenantIdAndSettingKey(tenantId, settingKey)
                .map(TenantSettingsEntity::getSettingValue)
                .orElse(null);
    }

    /**
     * Obtiene una configuración con valor por defecto
     */
    public String getSetting(Long tenantId, String settingKey, String defaultValue) {
        String value = getSetting(tenantId, settingKey);
        return value != null ? value : defaultValue;
    }

    /**
     * Obtiene una configuración booleana
     */
    public Boolean getBooleanSetting(Long tenantId, String settingKey, Boolean defaultValue) {
        String value = getSetting(tenantId, settingKey);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Obtiene una configuración BigDecimal
     */
    public BigDecimal getBigDecimalSetting(Long tenantId, String settingKey, BigDecimal defaultValue) {
        String value = getSetting(tenantId, settingKey);
        if (value == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            logger.error("Tenant {}: Error parsing BigDecimal setting '{}': {}",
                    tenantId, settingKey, e.getMessage());
            return defaultValue;
        }
    }

    /**
     * Obtiene lista de IDs de ciudades activas para un tenant
     */
    public List<Integer> getActiveCityIds(Long tenantId) {
        String cityIdsStr = getSetting(tenantId, "active_city_ids");

        if (cityIdsStr == null || cityIdsStr.trim().isEmpty()) {
            logger.warn("Tenant {}: No active_city_ids configured, using empty list", tenantId);
            return List.of();
        }

        try {
            return Arrays.stream(cityIdsStr.split(","))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Tenant {}: Error parsing active_city_ids '{}': {}",
                    tenantId, cityIdsStr, e.getMessage());
            return List.of();
        }
    }

    /**
     * Obtiene dominio de email para riders
     */
    public String getEmailDomain(Long tenantId) {
        return getSetting(tenantId, "rider_email_domain", "ritrack");
    }

    /**
     * Obtiene base de email para riders
     */
    public String getEmailBase(Long tenantId) {
        return getSetting(tenantId, "rider_email_base", "gmail.com");
    }

    /**
     * Obtiene prefijo de nombre para riders
     */
    public String getNameBase(Long tenantId) {
        return getSetting(tenantId, "rider_name_base", "Rider");
    }

    /**
     * Obtiene lista de vehicle type IDs por defecto
     */
    public List<Integer> getDefaultVehicleTypeIds(Long tenantId) {
        String vehicleIdsStr = getSetting(tenantId, "default_vehicle_type_ids", "5,1,3,2");

        try {
            return Arrays.stream(vehicleIdsStr.split(","))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Tenant {}: Error parsing default_vehicle_type_ids: {}",
                    tenantId, e.getMessage());
            return List.of(5, 1, 3, 2); // Fallback
        }
    }

    /**
     * Obtiene contract ID para riders desde glovo_credentials
     */
    public Integer getContractId(Long tenantId) {
        GlovoCredentialsEntity credentials = credentialsRepository.findByTenantId(tenantId)
                .orElseThrow(() -> {
                    logger.error("Tenant {}: Glovo credentials not found", tenantId);
                    return new IllegalStateException("Credenciales de Glovo no encontradas para tenant " + tenantId);
                });

        Integer contractId = credentials.getContractId();
        if (contractId == null) {
            logger.error("Tenant {}: contract_id is null in glovo_credentials", tenantId);
            throw new IllegalStateException("Contract ID no configurado en credenciales para tenant " + tenantId);
        }

        logger.debug("Tenant {}: contract_id from glovo_credentials: {}", tenantId, contractId);
        return contractId;
    }

    /**
     * Obtiene HargosAuth tenant ID desde la tabla tenants
     */
    public Long getHargosTenantId(Long tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElse(null);

        if (tenant == null) {
            logger.warn("Tenant {} not found", tenantId);
            return null;
        }

        Long hargosTenantId = tenant.getHargosTenantId();
        logger.debug("Tenant {}: hargosTenantId = {}", tenantId, hargosTenantId);
        return hargosTenantId;
    }

    // ========================================
    // UPDATE METHODS
    // ========================================

    /**
     * Actualiza configuraciones de tenant parcialmente.
     * Solo actualiza los campos que no son null en el request.
     *
     * @param tenantId ID del tenant
     * @param request DTO con campos opcionales a actualizar
     */
    @Transactional
    @CacheEvict(value = "tenant-settings", allEntries = true)
    public void updateSettings(Long tenantId, UpdateSettingsRequest request) {
        // Verificar que el tenant existe
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant no encontrado: " + tenantId));

        int updatedCount = 0;

        // Actualizar ciudades activas
        if (request.getActiveCityIds() != null) {
            String cityIdsStr = request.getActiveCityIds().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            updateSetting(tenant, "active_city_ids", cityIdsStr);
            updatedCount++;
        }

        // Actualizar configuración de email
        if (request.getEmailDomain() != null) {
            updateSetting(tenant, "rider_email_domain", request.getEmailDomain());
            updatedCount++;
        }

        if (request.getEmailBase() != null) {
            updateSetting(tenant, "rider_email_base", request.getEmailBase());
            updatedCount++;
        }

        if (request.getNameBase() != null) {
            updateSetting(tenant, "rider_name_base", request.getNameBase());
            updatedCount++;
        }

        // Actualizar tipos de vehículo por defecto
        if (request.getDefaultVehicleTypeIds() != null) {
            String vehicleIdsStr = request.getDefaultVehicleTypeIds().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            updateSetting(tenant, "default_vehicle_type_ids", vehicleIdsStr);
            updatedCount++;
        }

        // Actualizar company ID
        if (request.getCompanyId() != null) {
            updateSetting(tenant, "company_id", request.getCompanyId().toString());
            updatedCount++;
        }

        // Actualizar contract ID
        if (request.getContractId() != null) {
            updateSetting(tenant, "contract_id", request.getContractId().toString());
            updatedCount++;
        }

        // Actualizar base URLs
        if (request.getRoosterBaseUrl() != null) {
            updateSetting(tenant, "rooster_base_url", request.getRoosterBaseUrl());
            updatedCount++;
        }

        if (request.getLiveBaseUrl() != null) {
            updateSetting(tenant, "live_base_url", request.getLiveBaseUrl());
            updatedCount++;
        }

        logger.info("Tenant {}: Actualizados {} settings", tenantId, updatedCount);
    }

    /**
     * Actualiza o crea un setting individual para un tenant.
     *
     * @param tenant TenantEntity
     * @param key Clave del setting
     * @param value Valor del setting
     */
    @Transactional
    private void updateSetting(TenantEntity tenant, String key, String value) {
        TenantSettingsEntity setting = settingsRepository
                .findByTenantIdAndSettingKey(tenant.getId(), key)
                .orElse(TenantSettingsEntity.builder()
                        .tenant(tenant)
                        .settingKey(key)
                        .build());

        setting.setSettingValue(value);
        settingsRepository.save(setting);

        logger.debug("Tenant {}: Setting '{}' = '{}'", tenant.getId(), key, value);
    }

    /**
     * Guarda un setting individual (versión pública para servicios externos).
     *
     * @param tenantId Tenant ID
     * @param key Clave del setting
     * @param value Valor del setting
     */
    @Transactional
    @CacheEvict(value = "tenant-settings", key = "#tenantId + '-' + #key")
    public void saveSetting(Long tenantId, String key, String value) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant no encontrado: " + tenantId));

        updateSetting(tenant, key, value);
    }
}
