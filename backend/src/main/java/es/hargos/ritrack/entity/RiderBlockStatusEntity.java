package es.hargos.ritrack.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad que almacena el estado de bloqueo de cada rider.
 *
 * MULTI-TENANT: Esta entidad vive en el schema de cada tenant.
 *
 * Campos principales:
 * - is_auto_blocked: true si está bloqueado automáticamente por saldo alto
 * - is_manual_blocked: true si fue bloqueado manualmente por un admin (TIENE PRIORIDAD)
 * - last_balance: Último balance conocido (evita re-procesar riders sin cambios)
 */
@Entity
@Table(name = "rider_block_status")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiderBlockStatusEntity {

    @Id
    @Column(name = "employee_id", length = 255, nullable = false)
    private String employeeId;

    @Column(name = "is_auto_blocked", nullable = false)
    @Builder.Default
    private Boolean isAutoBlocked = false;

    @Column(name = "is_manual_blocked", nullable = false)
    @Builder.Default
    private Boolean isManualBlocked = false;

    @Column(name = "last_balance", precision = 10, scale = 2)
    private BigDecimal lastBalance;

    @Column(name = "last_balance_check")
    private LocalDateTime lastBalanceCheck;

    @Column(name = "auto_blocked_at")
    private LocalDateTime autoBlockedAt;

    @Column(name = "auto_unblocked_at")
    private LocalDateTime autoUnblockedAt;

    @Column(name = "manual_blocked_at")
    private LocalDateTime manualBlockedAt;

    @Column(name = "manual_blocked_by_user_id")
    private Long manualBlockedByUserId;

    @Column(name = "manual_block_reason", columnDefinition = "TEXT")
    private String manualBlockReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
