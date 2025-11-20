package es.hargos.ritrack.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity for tracking rider limit warnings per tenant.
 *
 * When a tenant exceeds their subscription rider limit (e.g., has 100 riders but paid for 10),
 * a warning is created with a 7-day grace period. After expiration, the tenant is blocked from
 * creating new riders until they upgrade their subscription or reduce their rider count.
 *
 * Multi-tenant: This table exists in each tenant's schema (e.g., arendel.rider_limit_warnings)
 */
@Entity
@Table(name = "rider_limit_warnings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiderLimitWarningEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Number of riders currently in Glovo API (actual count)
     */
    @Column(name = "current_count", nullable = false)
    private Integer currentCount;

    /**
     * Maximum riders allowed by subscription (from HargosAuth tenant_riders_config.rider_limit)
     */
    @Column(name = "allowed_limit", nullable = false)
    private Integer allowedLimit;

    /**
     * Number of riders exceeding the limit (current_count - allowed_limit)
     */
    @Column(name = "excess_count", nullable = false)
    private Integer excessCount;

    /**
     * When the grace period expires (typically 7 days from creation)
     * After this date, tenant cannot create new riders until resolved
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * Whether the warning has been resolved (tenant upgraded subscription or reduced riders)
     */
    @Column(name = "is_resolved", nullable = false)
    private Boolean isResolved = false;

    /**
     * When the warning was resolved
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /**
     * Who resolved the warning (user email or "system")
     */
    @Column(name = "resolved_by")
    private String resolvedBy;

    /**
     * Note about how the warning was resolved
     */
    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (isResolved == null) {
            isResolved = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Check if the grace period has expired
     *
     * @return true if current time is after expires_at
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if the warning is currently active (not resolved and not expired)
     *
     * @return true if active
     */
    public boolean isActive() {
        return !isResolved && !isExpired();
    }
}
