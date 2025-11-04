package es.hargos.ritrack.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Flexible key-value configuration storage per tenant.
 * Allows storing various settings without schema changes.
 */
@Entity
@Table(name = "tenant_settings", schema = "public",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "setting_key"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantSettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "setting_key", nullable = false)
    private String settingKey;

    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String settingValue;

    /**
     * Data type for proper parsing: STRING, NUMBER, BOOLEAN, JSON
     */
    @Column(name = "setting_type", length = 50)
    private String settingType = "STRING";

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

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

    public enum SettingType {
        STRING,
        NUMBER,
        BOOLEAN,
        JSON
    }
}
