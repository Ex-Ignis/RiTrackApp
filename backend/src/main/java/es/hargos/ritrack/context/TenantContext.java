package es.hargos.ritrack.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Thread-local context for storing current request's tenant information.
 * This is set by JwtAuthenticationFilter after validating the JWT token.
 *
 * Usage:
 * <pre>
 * // Set context (done by JwtAuthenticationFilter)
 * TenantContext.setCurrentContext(context);
 *
 * // Get context in services
 * TenantContext context = TenantContext.getCurrentContext();
 * List<Long> tenantIds = context.getTenantIds();
 *
 * // Clear context (done by filter after request)
 * TenantContext.clear();
 * </pre>
 */
public class TenantContext {

    private static final ThreadLocal<TenantInfo> CONTEXT = new ThreadLocal<>();

    /**
     * Set the tenant context for the current request thread
     */
    public static void setCurrentContext(TenantInfo tenantInfo) {
        CONTEXT.set(tenantInfo);
    }

    /**
     * Get the tenant context for the current request thread
     * @return TenantInfo or null if not set
     */
    public static TenantInfo getCurrentContext() {
        return CONTEXT.get();
    }

    /**
     * Clear the tenant context (MUST be called after request completion)
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Tenant information extracted from JWT token
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantInfo {
        /**
         * User ID from HargosAuthSystem
         */
        private Long userId;

        /**
         * User email
         */
        private String email;

        /**
         * List of tenant IDs the user has access to in RiTrack app
         */
        private List<Long> tenantIds;

        /**
         * List of tenant names (for logging/debugging)
         */
        private List<String> tenantNames;

        /**
         * List of schema names corresponding to tenantIds
         */
        private List<String> schemaNames;

        /**
         * User's roles per tenant (optional, for future use)
         */
        private List<String> roles;

        /**
         * The currently selected tenant ID for this request
         * This is set from the request header or parameter
         */
        private Long selectedTenantId;

        /**
         * Check if user has access to a specific tenant
         */
        public boolean hasAccessToTenant(Long tenantId) {
            return tenantIds != null && tenantIds.contains(tenantId);
        }

        /**
         * Get the first tenant ID (useful for single-tenant operations)
         */
        public Long getFirstTenantId() {
            return tenantIds != null && !tenantIds.isEmpty() ? tenantIds.get(0) : null;
        }

        /**
         * Get the first schema name
         */
        public String getFirstSchemaName() {
            return schemaNames != null && !schemaNames.isEmpty() ? schemaNames.get(0) : null;
        }

        /**
         * Get the schema name for the selected tenant ID.
         * This is the correct schema to use for multi-tenant operations.
         *
         * @return Schema name for the selected tenant, or first schema as fallback
         */
        public String getSelectedSchemaName() {
            // If there's a selected tenant ID, find its corresponding schema
            if (selectedTenantId != null && tenantIds != null && schemaNames != null) {
                int index = tenantIds.indexOf(selectedTenantId);
                if (index >= 0 && index < schemaNames.size()) {
                    return schemaNames.get(index);
                }
            }

            // Fallback to first schema if no selectedTenantId
            return getFirstSchemaName();
        }
    }
}
