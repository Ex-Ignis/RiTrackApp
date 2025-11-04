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

        private final DataSource dataSource;

        public TenantSchemaConnectionProvider(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Connection getAnyConnection() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void releaseAnyConnection(Connection connection) throws SQLException {
            connection.close();
        }

        @Override
        public Connection getConnection(String tenantIdentifier) throws SQLException {
            Connection connection = dataSource.getConnection();

            // Set search_path to tenant schema
            if (tenantIdentifier != null && !tenantIdentifier.isEmpty()) {
                try (var stmt = connection.createStatement()) {
                    // Set search_path to: tenant_schema, public (fallback for shared tables)
                    stmt.execute("SET search_path TO " + tenantIdentifier + ", public");
                }
            }

            return connection;
        }

        @Override
        public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
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

            @Override
            public String resolveCurrentTenantIdentifier() {
                TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

                if (tenantInfo != null && tenantInfo.getFirstSchemaName() != null) {
                    return tenantInfo.getFirstSchemaName();
                }

                // Default to public schema if no tenant context
                return "public";
            }

            @Override
            public boolean validateExistingCurrentSessions() {
                return false;
            }
        };
    }
}
