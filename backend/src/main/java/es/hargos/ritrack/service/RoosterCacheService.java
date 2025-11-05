package es.hargos.ritrack.service
        ;

import es.hargos.ritrack.client.GlovoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Servicio de caché SOLO para empleados de Rooster
 * Un único caché con TTL de 30 minutos
 */
@Service
public class RoosterCacheService {

    @Value("${cache.rooster.employees.ttl-minutes:30}")
    private long roosterTtlMinutes;

    private static final Logger logger = LoggerFactory.getLogger(RoosterCacheService.class);
    private final GlovoClient glovoClient;

    public RoosterCacheService(GlovoClient glovoClient) {
        this.glovoClient = glovoClient;
    }

    /**
     * Obtiene TODOS los empleados con caché de 30 minutos por tenant
     * Esta es la ÚNICA fuente de datos de Rooster
     */
    @Cacheable(value = "rooster-employees", key = "#tenantId")
    public List<?> getAllEmployees(Long tenantId) throws Exception {
        logger.info("Tenant {}: CACHE MISS - Cargando todos los empleados de Rooster API", tenantId);
        List<?> employees = glovoClient.obtenerEmpleados(tenantId);
        logger.info("Tenant {}: Cargados {} empleados en caché (TTL: {} min)", tenantId, employees.size(), roosterTtlMinutes);
        return employees;
    }

    /**
     * Limpia el caché de un tenant específico
     * MULTI-TENANT: Solo invalida el cache de un tenant, no de todos
     */
    @CacheEvict(value = "rooster-employees", key = "#tenantId")
    public void clearCache(Long tenantId) {
        logger.info("Tenant {}: Caché de empleados limpiado", tenantId);
    }

    /**
     * Limpia el caché de TODOS los tenants (usar con precaución)
     * Solo para mantenimiento o emergencias
     */
    @CacheEvict(value = "rooster-employees", allEntries = true)
    public void clearAllTenantsCache() {
        logger.warn("ADVERTENCIA: Caché de empleados limpiado para TODOS los tenants");
    }
}