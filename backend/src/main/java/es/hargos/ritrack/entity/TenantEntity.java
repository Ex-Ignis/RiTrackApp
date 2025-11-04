package es.hargos.ritrack.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Tenant entity - Represents a client company in the multi-tenant RiTrack system.
 * Each tenant has its own PostgreSQL schema for data isolation.
 */
@Entity
@Table(name = "tenants", schema = "public")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tenant ID from HargosAuth (auth.tenants.id)
     * Used to link this tenant with HargosAuth's tenant registry
     */
    @Column(name = "hargos_tenant_id", unique = true)
    private Long hargosTenantId;

    /**
     * Tenant display name (e.g., "Arendel", "Entregalia")
     */
    @Column(nullable = false, unique = true)
    private String name;

    /**
     * PostgreSQL schema name for data isolation (e.g., "arendel", "entregalia")
     * Must be lowercase, alphanumeric + underscores
     */
    @Column(name = "schema_name", nullable = false, unique = true, length = 100)
    private String schemaName;

    /**
     * Tenant active status
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
