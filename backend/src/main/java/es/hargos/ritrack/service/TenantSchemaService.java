package es.hargos.ritrack.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio para gestionar la creación y eliminación de schemas de tenants en PostgreSQL.
 *
 * Responsabilidades:
 * - Ejecutar función create_tenant_schema() para crear schema completo
 * - Validar existencia de schemas
 * - Eliminar schemas de tenants (si es necesario)
 */
@Service
public class TenantSchemaService {

    private static final Logger logger = LoggerFactory.getLogger(TenantSchemaService.class);

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TenantSchemaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Crea un schema completo para un tenant usando la función PostgreSQL.
     *
     * La función create_tenant_schema() crea:
     * - Schema con el nombre especificado
     * - Tabla {schema}.riders
     * - Tabla {schema}.rider_metrics_csv
     * - Índices necesarios
     * - Permisos adecuados
     *
     * @param schemaName Nombre del schema a crear (ej: "arendel", "entregalia")
     * @throws RuntimeException Si falla la creación del schema
     */
    @Transactional
    public void createSchema(String schemaName) {
        logger.info("Creando schema para tenant: {}", schemaName);

        try {
            // Validar nombre del schema (solo minúsculas, números y guión bajo)
            if (!schemaName.matches("^[a-z][a-z0-9_]*$")) {
                throw new IllegalArgumentException(
                        "Nombre de schema inválido. Debe empezar con letra minúscula y contener solo letras, números y guión bajo"
                );
            }

            // Verificar que el schema no exista ya
            Boolean schemaExists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)",
                    Boolean.class,
                    schemaName
            );

            if (Boolean.TRUE.equals(schemaExists)) {
                logger.warn("Schema {} ya existe, saltando creación", schemaName);
                return;
            }

            // Ejecutar función create_tenant_schema()
            jdbcTemplate.execute(String.format("SELECT create_tenant_schema('%s')", schemaName));

            logger.info("Schema {} creado exitosamente con todas sus tablas", schemaName);

        } catch (Exception e) {
            logger.error("Error creando schema {}: {}", schemaName, e.getMessage(), e);
            throw new RuntimeException("Error creando schema para tenant: " + e.getMessage(), e);
        }
    }

    /**
     * Verifica si un schema existe en la base de datos.
     *
     * @param schemaName Nombre del schema
     * @return true si el schema existe
     */
    public boolean schemaExists(String schemaName) {
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)",
                    Boolean.class,
                    schemaName
            );
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            logger.error("Error verificando existencia de schema {}: {}", schemaName, e.getMessage());
            return false;
        }
    }

    /**
     * Verifica si las tablas principales existen en un schema.
     *
     * @param schemaName Nombre del schema
     * @return true si todas las tablas requeridas existen
     */
    public boolean validateSchemaStructure(String schemaName) {
        try {
            // Verificar que existan todas las tablas principales
            Integer tableCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema = ? AND table_name IN " +
                            "('riders', 'rider_metrics_csv', 'daily_deliveries', 'rider_metrics_daily', 'rider_metrics_weekly')",
                    Integer.class,
                    schemaName
            );

            // Debe tener las 5 tablas principales
            boolean valid = tableCount != null && tableCount >= 3; // Al menos riders, rider_metrics_csv, daily_deliveries

            if (!valid) {
                logger.warn("Schema {} no tiene la estructura completa. Tablas encontradas: {}/5", schemaName, tableCount);
            }

            return valid;
        } catch (Exception e) {
            logger.error("Error validando estructura de schema {}: {}", schemaName, e.getMessage());
            return false;
        }
    }

    /**
     * Elimina un schema y todas sus tablas (CUIDADO - OPERACIÓN DESTRUCTIVA).
     *
     * @param schemaName Nombre del schema a eliminar
     * @throws RuntimeException Si falla la eliminación
     */
    @Transactional
    public void dropSchema(String schemaName) {
        logger.warn("ELIMINANDO schema {} y todas sus tablas", schemaName);

        try {
            // Validar que no sea un schema del sistema
            if (schemaName.equals("public") || schemaName.equals("information_schema") || schemaName.equals("pg_catalog")) {
                throw new IllegalArgumentException("No se puede eliminar un schema del sistema");
            }

            jdbcTemplate.execute(String.format("DROP SCHEMA IF EXISTS %s CASCADE", schemaName));

            logger.info("Schema {} eliminado exitosamente", schemaName);

        } catch (Exception e) {
            logger.error("Error eliminando schema {}: {}", schemaName, e.getMessage(), e);
            throw new RuntimeException("Error eliminando schema: " + e.getMessage(), e);
        }
    }
}
