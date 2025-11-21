-- ==============================================
-- RITRACK DATABASE INITIALIZATION SCRIPT
-- Multi-Tenant Rider Management System
-- Database: ritrack_db
-- ==============================================
--
-- Architecture:
-- - Schema 'public': Multi-tenant configuration and shared tables
-- - Schema per tenant: {tenant_schema}.riders, metrics, etc.
--
-- Each tenant is isolated in their own PostgreSQL schema
-- ==============================================

-- ==============================================
-- SECTION 1: PUBLIC SCHEMA - MULTI-TENANT CONFIGURATION
-- ==============================================

-- Tenants table - Core tenant registry
CREATE TABLE IF NOT EXISTS public.tenants (
    id BIGSERIAL PRIMARY KEY,
    hargos_tenant_id BIGINT UNIQUE,
    name VARCHAR(255) UNIQUE NOT NULL,
    schema_name VARCHAR(100) UNIQUE NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_schema_name CHECK (schema_name ~ '^[a-z][a-z0-9_]*$')
);

COMMENT ON TABLE public.tenants IS 'Registry of all tenants in the RiTrack system';
COMMENT ON COLUMN public.tenants.schema_name IS 'PostgreSQL schema name for tenant data isolation';
COMMENT ON COLUMN public.tenants.hargos_tenant_id IS 'Tenant ID from HargosAuth - links to auth.tenants.id';


-- Glovo API credentials per tenant
CREATE TABLE IF NOT EXISTS public.glovo_credentials (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,

    -- OAuth2 JWT Client Credentials
    client_id VARCHAR(255) NOT NULL,
    key_id VARCHAR(255) NOT NULL,
    private_key_path VARCHAR(500) NOT NULL,

    -- OAuth2 endpoints
    audience_url VARCHAR(500) NOT NULL DEFAULT 'https://sts.deliveryhero.io',
    token_url VARCHAR(500) NOT NULL DEFAULT 'https://sts.dh-auth.io/oauth2/token',

    -- API base URLs
    rooster_base_url VARCHAR(500) NOT NULL DEFAULT 'https://gv-es.usehurrier.com/api/rooster',
    live_base_url VARCHAR(500) NOT NULL DEFAULT 'https://gv-es.usehurrier.com/api/rider-live-operations',

    -- Glovo company and contract IDs
    company_id INTEGER NOT NULL,
    contract_id INTEGER NOT NULL,

    -- Status and validation
    is_active BOOLEAN DEFAULT TRUE,
    is_validated BOOLEAN DEFAULT FALSE,
    last_validated_at TIMESTAMP,
    validation_error TEXT,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (tenant_id)
);

COMMENT ON TABLE public.glovo_credentials IS 'Glovo API OAuth2 credentials per tenant - secured storage';
COMMENT ON COLUMN public.glovo_credentials.is_validated IS 'True if credentials were successfully tested against Glovo API';
COMMENT ON COLUMN public.glovo_credentials.private_key_path IS 'Path to private key file for JWT signing (e.g., /keys/tenant_123.pem)';


-- Tenant settings - Flexible key-value configuration
CREATE TABLE IF NOT EXISTS public.tenant_settings (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    setting_key VARCHAR(255) NOT NULL,
    setting_value TEXT,
    setting_type VARCHAR(50) DEFAULT 'STRING', -- STRING, NUMBER, BOOLEAN, JSON
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (tenant_id, setting_key)
);

COMMENT ON TABLE public.tenant_settings IS 'Flexible configuration storage per tenant';
COMMENT ON COLUMN public.tenant_settings.setting_type IS 'Data type for proper parsing: STRING, NUMBER, BOOLEAN, JSON';


-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_tenants_schema_name ON public.tenants(schema_name) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_glovo_credentials_tenant_id ON public.glovo_credentials(tenant_id);
CREATE INDEX IF NOT EXISTS idx_glovo_credentials_active ON public.glovo_credentials(tenant_id, is_active) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_tenant_settings_tenant_key ON public.tenant_settings(tenant_id, setting_key);


-- ==============================================
-- SECTION 2: TENANT SCHEMA TEMPLATE FUNCTION
-- ==============================================

-- Function to create a new tenant schema with all required tables
CREATE OR REPLACE FUNCTION create_tenant_schema(tenant_schema_name VARCHAR(100))
RETURNS VOID AS $$
BEGIN
    -- Create schema
    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', tenant_schema_name);

    -- Set search path
    EXECUTE format('SET search_path TO %I, public', tenant_schema_name);

    -- Create rider_metrics_csv table (imported from CSV files)
    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.rider_metrics_csv (
            id BIGSERIAL PRIMARY KEY,
            employee_id VARCHAR(255) NOT NULL,
            date DATE NOT NULL,
            city_id INTEGER,
            hours_worked DECIMAL(10, 2),
            deliveries_completed INTEGER,
            deliveries_cancelled INTEGER,
            acceptance_rate DECIMAL(5, 2),
            rejection_rate DECIMAL(5, 2),
            average_rating DECIMAL(3, 2),
            orders_per_hour DECIMAL(10, 2),
            earnings DECIMAL(10, 2),
            tips DECIMAL(10, 2),
            distance_km DECIMAL(10, 2),
            utilization_rate DECIMAL(5, 2),
            break_time_minutes INTEGER,
            late_time_minutes INTEGER,
            metadata JSONB,
            imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

            UNIQUE (employee_id, date)
        )
    ', tenant_schema_name);

    -- Create rider_metrics_daily table (imported from CSV files)
    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.rider_metrics_daily (
            id BIGSERIAL PRIMARY KEY,
            rider_id INTEGER NOT NULL,
            day DATE NOT NULL,
            city VARCHAR(100),
            vehicle VARCHAR(50),
            phone VARCHAR(50),
            worked_hours DECIMAL(10, 2),
            total_completed_deliveries INTEGER,
            total_assigned INTEGER,
            total_reassigned INTEGER,
            total_cancelled_deliveries INTEGER,
            total_cancelled_near_customer INTEGER,
            utr DECIMAL(10, 2),
            efficiency DECIMAL(10, 2),
            total_stacked_deliveries INTEGER,
            total_stacked_intravendor INTEGER,
            total_stacked_intervendor INTEGER,
            driven_distance_km DECIMAL(10, 2),
            total_wtp_min DECIMAL(10, 2),
            total_wtd_min DECIMAL(10, 2),
            booked_shifts INTEGER,
            unbooked_shifts INTEGER,
            balance_eod VARCHAR(50),
            total_cdt DECIMAL(10, 2),
            avg_cdt DECIMAL(10, 2),
            pd_speed_kmh DECIMAL(10, 2),
            tips DECIMAL(10, 2),
            imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
        )
    ', tenant_schema_name);

    -- Create rider_metrics_weekly table (imported from CSV files)
    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.rider_metrics_weekly (
            id BIGSERIAL PRIMARY KEY,
            rider_id INTEGER NOT NULL,
            week VARCHAR(20) NOT NULL,
            city VARCHAR(100),
            vehicle VARCHAR(50),
            phone VARCHAR(50),
            worked_hours DECIMAL(10, 2),
            total_completed_deliveries INTEGER,
            total_assigned INTEGER,
            total_reassigned INTEGER,
            total_cancelled_deliveries INTEGER,
            total_cancelled_near_customer INTEGER,
            utr DECIMAL(10, 2),
            efficiency DECIMAL(10, 2),
            total_stacked_deliveries INTEGER,
            total_stacked_intravendor INTEGER,
            total_stacked_intervendor INTEGER,
            driven_distance_km DECIMAL(10, 2),
            total_wtp_min DECIMAL(10, 2),
            total_wtd_min DECIMAL(10, 2),
            booked_shifts INTEGER,
            unbooked_shifts INTEGER,
            balance_eod VARCHAR(50),
            total_cdt DECIMAL(10, 2),
            avg_cdt DECIMAL(10, 2),
            pd_speed_kmh DECIMAL(10, 2),
            tips DECIMAL(10, 2),
            imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
        )
    ', tenant_schema_name);

    -- Create indexes for metrics
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_metrics_csv_employee_date ON %I.rider_metrics_csv(employee_id, date)',
        tenant_schema_name, tenant_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_metrics_csv_date ON %I.rider_metrics_csv(date DESC)',
        tenant_schema_name, tenant_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_metrics_csv_city_date ON %I.rider_metrics_csv(city_id, date)',
        tenant_schema_name, tenant_schema_name);

    -- Create indexes for rider_metrics_daily
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_metrics_daily_rider_day ON %I.rider_metrics_daily(rider_id, day)',
        tenant_schema_name, tenant_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_metrics_daily_day ON %I.rider_metrics_daily(day DESC)',
        tenant_schema_name, tenant_schema_name);

    -- Create indexes for rider_metrics_weekly
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_metrics_weekly_rider_week ON %I.rider_metrics_weekly(rider_id, week)',
        tenant_schema_name, tenant_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_metrics_weekly_week ON %I.rider_metrics_weekly(week DESC)',
        tenant_schema_name, tenant_schema_name);

    -- Create rider_block_status table (auto-block by cash balance)
    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.rider_block_status (
            employee_id VARCHAR(255) PRIMARY KEY,
            is_auto_blocked BOOLEAN NOT NULL DEFAULT false,
            is_manual_blocked BOOLEAN NOT NULL DEFAULT false,
            last_balance NUMERIC(10, 2),
            last_balance_check TIMESTAMP,
            auto_blocked_at TIMESTAMP,
            auto_unblocked_at TIMESTAMP,
            manual_blocked_at TIMESTAMP,
            manual_blocked_by_user_id BIGINT,
            manual_block_reason TEXT,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
    ', tenant_schema_name);

    -- Create indexes for rider_block_status
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_rider_block_status_is_auto_blocked ON %I.rider_block_status(is_auto_blocked)',
        tenant_schema_name, tenant_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_rider_block_status_last_balance_check ON %I.rider_block_status(last_balance_check DESC)',
        tenant_schema_name, tenant_schema_name);

    -- Create auto_block_city_config table (per-city auto-block configuration)
    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.auto_block_city_config (
            id BIGSERIAL PRIMARY KEY,
            city_id INTEGER NOT NULL UNIQUE,
            enabled BOOLEAN NOT NULL DEFAULT false,
            cash_limit NUMERIC(10, 2) NOT NULL DEFAULT 150.00,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

            CHECK (cash_limit > 0),
            CHECK (city_id > 0)
        )
    ', tenant_schema_name);

    -- Create indexes for auto_block_city_config
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_auto_block_city_config_city_id ON %I.auto_block_city_config(city_id)',
        tenant_schema_name, tenant_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_auto_block_city_config_enabled ON %I.auto_block_city_config(enabled) WHERE enabled = true',
        tenant_schema_name, tenant_schema_name);

    -- Create user_city_assignments table (city access control per user)
    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.user_city_assignments (
            id BIGSERIAL PRIMARY KEY,
            user_id BIGINT NOT NULL,
            city_id BIGINT NOT NULL,
            assigned_by_user_id BIGINT,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

            UNIQUE(user_id, city_id),
            CHECK (user_id > 0)
        )
    ', tenant_schema_name);

    -- Create indexes for user_city_assignments
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_user_city_user ON %I.user_city_assignments(user_id)',
        tenant_schema_name, tenant_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_user_city_city ON %I.user_city_assignments(city_id)',
        tenant_schema_name, tenant_schema_name);

    -- Create rider_limit_warnings table (warnings when tenant exceeds rider limit)
    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.rider_limit_warnings (
            id BIGSERIAL PRIMARY KEY,
            current_count INTEGER NOT NULL,
            allowed_limit INTEGER NOT NULL,
            excess_count INTEGER NOT NULL,
            is_resolved BOOLEAN NOT NULL DEFAULT false,
            resolved_at TIMESTAMP,
            resolved_by TEXT,
            resolution_note TEXT,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            expires_at TIMESTAMP
        )
    ', tenant_schema_name);

    -- Create indexes for rider_limit_warnings
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_rider_limit_warnings_resolved ON %I.rider_limit_warnings(is_resolved, created_at DESC)',
        tenant_schema_name, tenant_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%I_rider_limit_warnings_created ON %I.rider_limit_warnings(created_at DESC)',
        tenant_schema_name, tenant_schema_name);

    -- Grant permissions
    EXECUTE format('GRANT ALL PRIVILEGES ON SCHEMA %I TO ritrack', tenant_schema_name);
    EXECUTE format('GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA %I TO ritrack', tenant_schema_name);
    EXECUTE format('GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I TO ritrack', tenant_schema_name);

    RAISE NOTICE 'Tenant schema % created successfully', tenant_schema_name;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION create_tenant_schema IS 'Creates a complete tenant schema with riders and metrics tables';


-- ==============================================
-- SECTION 3: DEFAULT TENANTS (TESTING ONLY)
-- ==============================================
-- ⚠️ IMPORTANTE: En producción, los tenants se crean desde HargosAuth cuando el usuario compra el servicio
-- Esta sección es SOLO para desarrollo y testing local
--
-- FLUJO EN PRODUCCIÓN:
-- 1. Usuario compra RiTrack en HargosAuth
-- 2. HargosAuth inserta registro en public.tenants
-- 3. Usuario accede a RiTrack por primera vez
-- 4. RiTrack detecta que tenant necesita configuración (sin credenciales)
-- 5. Usuario completa onboarding vía POST /api/v1/tenant/onboarding/configure
-- 6. RiTrack crea schema, credenciales y settings automáticamente

-- ==============================================
-- SECTION 3.2: GLOVO CREDENTIALS Y SETTINGS
-- ==============================================
-- ⚠️ NO SE INSERTAN CREDENCIALES NI SETTINGS AQUÍ
--
-- CONFIGURACIÓN VÍA ONBOARDING API:
-- Las credenciales y settings se configuran cuando el usuario completa el onboarding:
--
-- POST /api/v1/tenant/onboarding/configure
-- Form-data:
--   - pemFile: archivo .pem con clave privada
--   - clientId: Client ID de Glovo
--   - keyId: Key ID de Glovo
--   - activeCityIds: 902,804,882 (ciudades a monitorear)
--   - emailDomain: nombre del tenant
--   - emailBase: gmail.com
--   - nameBase: Nombre del tenant
--   - companyId: (opcional, se auto-detecta)
--   - contractId: (opcional, se auto-detecta)
--
-- El backend automáticamente:
--   1. Valida credenciales con Glovo API
--   2. Guarda .pem en ./keys/tenant_{id}.pem
--   3. Inserta en public.glovo_credentials
--   4. Crea schema PostgreSQL con create_tenant_schema()
--      - rider_metrics_csv, rider_metrics_daily, rider_metrics_weekly
--      - rider_block_status (auto-bloqueo por cash)
--   5. Inserta settings en public.tenant_settings:
--      - active_city_ids
--      - rider_email_domain, rider_email_base, rider_name_base
--      - default_vehicle_type_ids
--      - auto_block_enabled = false (DESACTIVADO por defecto)
--      - auto_block_cash_limit = 150.00 (límite por defecto)
--   6. Activa el tenant (is_active = true)
--
-- ==============================================

-- NOTA: Los schemas NO se crean automáticamente en init
-- Se crean dinámicamente durante el onboarding vía create_tenant_schema()

-- ==============================================
-- CÓMO AGREGAR UN NUEVO TENANT (PRODUCCIÓN)
-- ==============================================
-- 1. HargosAuth inserta en public.tenants cuando usuario compra:
--    INSERT INTO public.tenants (name, schema_name, is_active)
--    VALUES ('NuevoTenant', 'nuevotenant', false);
--
-- 2. Usuario accede a RiTrack y completa onboarding
--
-- 3. RiTrack ejecuta automáticamente:
--    - INSERT INTO public.glovo_credentials (...)
--    - SELECT create_tenant_schema('nuevotenant')
--    - INSERT INTO public.tenant_settings (...)
--    - UPDATE public.tenants SET is_active = true
--
-- ==============================================


-- ==============================================
-- SECTION 4: GRANT PERMISSIONS
-- ==============================================

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO postgre;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO postgre;
GRANT EXECUTE ON FUNCTION create_tenant_schema TO postgre;


-- ==============================================
-- SECTION 5: SUMMARY
-- ==============================================

DO $$
BEGIN
    RAISE NOTICE '========================================';
    RAISE NOTICE 'RITRACK DATABASE INITIALIZED';
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Database: ritrack_db';
    RAISE NOTICE 'Multi-tenant architecture: ENABLED';
    RAISE NOTICE '';
    RAISE NOTICE 'Public schema created with tables:';
    RAISE NOTICE '  - public.tenants (tenant registry)';
    RAISE NOTICE '  - public.glovo_credentials (API credentials)';
    RAISE NOTICE '  - public.tenant_settings (configurations)';
    RAISE NOTICE '';
    RAISE NOTICE 'Default tenants for testing:';
    RAISE NOTICE '  - ID 1: Arendel (schema: arendel, is_active: false)';
    RAISE NOTICE '  - ID 2: Entregalia (schema: entregalia, is_active: false)';
    RAISE NOTICE '';
    RAISE NOTICE '⚠️  TENANTS NOT CONFIGURED - NEED ONBOARDING';
    RAISE NOTICE '';
    RAISE NOTICE 'To configure a tenant:';
    RAISE NOTICE '  1. Start backend: ./mvnw spring-boot:run';
    RAISE NOTICE '  2. Check status: GET /api/v1/tenant/onboarding/status?tenantId=2';
    RAISE NOTICE '  3. Configure: POST /api/v1/tenant/onboarding/configure?tenantId=2';
    RAISE NOTICE '';
    RAISE NOTICE 'Tenant schemas will be created dynamically during onboarding';
    RAISE NOTICE '========================================';
END $$;
