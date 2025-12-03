package es.hargos.ritrack.config;

import es.hargos.ritrack.context.TenantContext;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Hibernate configuration for multi-tenant schema-based isolation.
 *
 * This configuration sets the PostgreSQL search_path to the tenant's schema
 * before each transaction, allowing queries to execute in the correct schema.
 */
@Configuration
public class HibernateConfig {

    /**
     * Customizer that sets the search_path for each Hibernate session
     * based on the current tenant context.
     */
    @Bean
    public HibernatePropertiesCustomizer hibernatePropertiesCustomizer(
            DataSource dataSource,
            org.hibernate.context.spi.CurrentTenantIdentifierResolver<String> tenantResolver) {
        return (Map<String, Object> hibernateProperties) -> {
            hibernateProperties.put("hibernate.multi_tenant", "SCHEMA");
            hibernateProperties.put("hibernate.multi_tenant_connection_provider",
                new TenantSchemaConnectionProvider(dataSource));
            hibernateProperties.put("hibernate.tenant_identifier_resolver", tenantResolver);
        };
    }

    /**
     * Connection provider that sets the PostgreSQL search_path to the tenant schema
     */
    private static class TenantSchemaConnectionProvider implements org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider<String> {

        private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("TenantConnectionProvider");
        private final DataSource dataSource;

        public TenantSchemaConnectionProvider(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Connection getAnyConnection() throws SQLException {
            logger.warn("⚠️ [ConnectionProvider] getAnyConnection() called - NO tenant context");
            return dataSource.getConnection();
        }

        @Override
        public void releaseAnyConnection(Connection connection) throws SQLException {
            connection.close();
        }

        @Override
        public Connection getConnection(String tenantIdentifier) throws SQLException {
            logger.debug("[ConnectionProvider] getConnection() called for tenant: {}", tenantIdentifier);
            Connection connection = dataSource.getConnection();

            // Set search_path to tenant schema
            if (tenantIdentifier != null && !tenantIdentifier.isEmpty()) {
                try (var stmt = connection.createStatement()) {
                    String sql = "SET search_path TO " + tenantIdentifier + ", public";
                    logger.debug("[ConnectionProvider] Executing: {}", sql);
                    stmt.execute(sql);
                    logger.debug("[ConnectionProvider] search_path set successfully to: {}", tenantIdentifier);
                }
            } else {
                logger.debug("[ConnectionProvider] tenantIdentifier is null or empty, NOT setting search_path");
            }

            return connection;
        }

        @Override
        public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
            logger.debug("🔓 [ConnectionProvider] Releasing connection for tenant: {}", tenantIdentifier);
            // Reset search_path to default before returning to pool
            try (var stmt = connection.createStatement()) {
                stmt.execute("SET search_path TO public");
            }
            connection.close();
        }

        @Override
        public boolean supportsAggressiveRelease() {
            return false;
        }

        @Override
        public boolean isUnwrappableAs(Class<?> unwrapType) {
            return false;
        }

        @Override
        public <T> T unwrap(Class<T> unwrapType) {
            return null;
        }
    }

    /**
     * Resolver that determines the current tenant identifier from TenantContext
     */
    @Bean
    public org.hibernate.context.spi.CurrentTenantIdentifierResolver<String> currentTenantIdentifierResolver() {
        return new org.hibernate.context.spi.CurrentTenantIdentifierResolver<String>() {

            private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("TenantIdentifierResolver");

            @Override
            public String resolveCurrentTenantIdentifier() {
                TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

                if (tenantInfo != null) {
                    logger.debug("🔍 [HibernateConfig] TenantContext found - selectedTenantId: {}, tenantIds: {}, schemaNames: {}",
                        tenantInfo.getSelectedTenantId(),
                        tenantInfo.getTenantIds(),
                        tenantInfo.getSchemaNames());

                    // IMPORTANTE: Usar getSelectedSchemaName() en lugar de getFirstSchemaName()
                    // para respetar el tenant seleccionado por el usuario
                    String schemaName = tenantInfo.getSelectedSchemaName();

                    if (schemaName != null && !schemaName.isEmpty()) {
                        logger.debug("✅ [HibernateConfig] Resolved tenant schema: {}", schemaName);
                        return schemaName;
                    }

                    // Fallback al primer schema si no hay selectedSchema
                    if (tenantInfo.getFirstSchemaName() != null) {
                        logger.warn("⚠️ [HibernateConfig] No selectedSchemaName found, falling back to first schema: {}",
                            tenantInfo.getFirstSchemaName());
                        return tenantInfo.getFirstSchemaName();
                    }
                }

                // Default to public schema if no tenant context
                logger.debug("[HibernateConfig] No tenant context found, defaulting to 'public' schema");
                return "public";
            }

            @Override
            public boolean validateExistingCurrentSessions() {
                return false;
            }
        };
    }
}
