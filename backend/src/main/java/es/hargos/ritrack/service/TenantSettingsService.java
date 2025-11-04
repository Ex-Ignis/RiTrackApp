package es.hargos.ritrack.service;

import es.hargos.ritrack.entity.TenantSettingsEntity;
import es.hargos.ritrack.repository.TenantSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio para leer configuraciones específicas de cada tenant desde la base de datos.
 * Reemplaza configuraciones hardcodeadas en application.properties.
 */
@Service
public class TenantSettingsService {

    private static final Logger logger = LoggerFactory.getLogger(TenantSettingsService.class);
    private final TenantSettingsRepository settingsRepository;

    public TenantSettingsService(TenantSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
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
}
