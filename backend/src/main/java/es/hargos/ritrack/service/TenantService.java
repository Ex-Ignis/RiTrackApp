package es.hargos.ritrack.service;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.entity.GlovoCredentialsEntity;
import es.hargos.ritrack.entity.TenantEntity;
import es.hargos.ritrack.repository.GlovoCredentialsRepository;
import es.hargos.ritrack.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for tenant-related operations.
 * Handles tenant lookup, schema mapping, and Glovo credentials retrieval.
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private GlovoCredentialsRepository glovoCredentialsRepository;

    /**
     * Get tenant by ID
     */
    public Optional<TenantEntity> getTenantById(Long tenantId) {
        return tenantRepository.findById(tenantId);
    }

    /**
     * Get tenant by schema name
     */
    @Cacheable(value = "tenantsBySchema", key = "#schemaName")
    public Optional<TenantEntity> getTenantBySchemaName(String schemaName) {
        return tenantRepository.findBySchemaName(schemaName);
    }

    /**
     * Get all active tenants
     */
    @Cacheable(value = "activeTenants")
    public List<TenantEntity> getActiveTenants() {
        return tenantRepository.findByIsActive(true);
    }

    /**
     * Get Glovo credentials for a tenant
     */
    @Cacheable(value = "glovoCredentials", key = "#tenantId")
    public Optional<GlovoCredentialsEntity> getGlovoCredentials(Long tenantId) {
        return glovoCredentialsRepository.findByTenantIdAndIsActive(tenantId, true);
    }

    /**
     * Get schema names from tenant IDs
     */
    public List<String> getSchemaNamesFromTenantIds(List<Long> tenantIds) {
        List<String> schemaNames = new ArrayList<>();
        
        for (Long tenantId : tenantIds) {
            tenantRepository.findById(tenantId).ifPresent(tenant -> {
                if (tenant.getIsActive()) {
                    schemaNames.add(tenant.getSchemaName());
                }
            });
        }
        
        return schemaNames;
    }

    /**
     * Get current tenant schemas from TenantContext
     */
    public List<String> getCurrentTenantSchemas() {
        TenantContext.TenantInfo context = TenantContext.getCurrentContext();
        
        if (context == null) {
            log.warn("No TenantContext found - request may not be authenticated");
            return new ArrayList<>();
        }
        
        return context.getSchemaNames() != null ? context.getSchemaNames() : new ArrayList<>();
    }

    /**
     * Get first tenant schema from context (for single-tenant operations)
     */
    public String getFirstTenantSchema() {
        TenantContext.TenantInfo context = TenantContext.getCurrentContext();
        
        if (context == null) {
            throw new IllegalStateException("No TenantContext found - user not authenticated");
        }
        
        String schema = context.getFirstSchemaName();
        if (schema == null) {
            throw new IllegalStateException("No tenant schema found for user");
        }
        
        return schema;
    }

    /**
     * Verify user has access to a specific tenant
     */
    public boolean hasAccessToTenant(Long tenantId) {
        TenantContext.TenantInfo context = TenantContext.getCurrentContext();
        return context != null && context.hasAccessToTenant(tenantId);
    }

    /**
     * Get tenant IDs from current context
     */
    public List<Long> getCurrentTenantIds() {
        TenantContext.TenantInfo context = TenantContext.getCurrentContext();
        return context != null ? context.getTenantIds() : new ArrayList<>();
    }

    /**
     * Check if a schema name exists and is active
     */
    public boolean isValidSchema(String schemaName) {
        return tenantRepository.findBySchemaName(schemaName)
                .map(TenantEntity::getIsActive)
                .orElse(false);
    }

    /**
     * Get Glovo credentials for current tenant (first tenant in context)
     */
    public Optional<GlovoCredentialsEntity> getCurrentTenantGlovoCredentials() {
        TenantContext.TenantInfo context = TenantContext.getCurrentContext();

        if (context == null || context.getFirstTenantId() == null) {
            log.warn("No tenant context found when fetching Glovo credentials");
            return Optional.empty();
        }

        return getGlovoCredentials(context.getFirstTenantId());
    }

    /**
     * Find or create a tenant by HargosAuth tenant ID.
     * Auto-creates tenant on first access from HargosAuth.
     *
     * @param hargosTenantId Tenant ID from HargosAuth (auth.tenants.id)
     * @param tenantName Tenant name from JWT
     * @return Tenant entity (existing or newly created)
     */
    @Transactional
    public TenantEntity findOrCreateByHargosTenantId(Long hargosTenantId, String tenantName) {
        return tenantRepository.findByHargosTenantId(hargosTenantId)
                .orElseGet(() -> {
                    log.info("🆕 Auto-creating tenant '{}' (HargosAuth ID: {})", tenantName, hargosTenantId);

                    String schemaName = generateSchemaName(tenantName);

                    TenantEntity tenant = TenantEntity.builder()
                            .hargosTenantId(hargosTenantId)
                            .name(tenantName)
                            .schemaName(schemaName)
                            .isActive(false)  // Needs onboarding
                            .build();

                    return tenantRepository.save(tenant);
                });
    }

    /**
     * Generate a valid PostgreSQL schema name from tenant name.
     * - Converts to lowercase
     * - Removes spaces and special characters
     * - Only allows alphanumeric + underscores
     *
     * @param tenantName Original tenant name (e.g., "Arendel", "Entrega Rápida")
     * @return Valid schema name (e.g., "arendel", "entregarapida")
     */
    private String generateSchemaName(String tenantName) {
        return tenantName.toLowerCase()
                .replaceAll("[^a-z0-9_]", "")
                .replaceAll("\\s+", "");
    }
}
