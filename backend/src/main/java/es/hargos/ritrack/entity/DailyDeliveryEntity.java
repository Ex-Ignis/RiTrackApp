package es.hargos.ritrack.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Registro de entregas diarias de riders sincronizadas desde Glovo Live API.
 * Tabla multi-tenant: cada tenant tiene sus registros en su propio schema.
 */
@Entity
@Table(name = "daily_deliveries",
       uniqueConstraints = @UniqueConstraint(columnNames = {"rider_id", "date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyDeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rider_id", nullable = false)
    private String riderId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "completed_deliveries")
    private Integer completedDeliveries;

    @Column(name = "cancelled_deliveries")
    private Integer cancelledDeliveries;

    @Column(name = "accepted_deliveries")
    private Integer acceptedDeliveries;

    @Column(name = "total_worked_seconds")
    private Integer totalWorkedSeconds;

    @Column(name = "total_break_seconds")
    private Integer totalBreakSeconds;

    @Column(name = "last_session_deliveries")
    private Integer lastSessionDeliveries;

    @Column(name = "last_session_cancelled")
    private Integer lastSessionCancelled;

    @Column(name = "last_session_worked_seconds")
    private Integer lastSessionWorkedSeconds;

    @Column(name = "utilization_rate")
    private Double utilizationRate;

    @Column(name = "acceptance_rate")
    private Double acceptanceRate;

    @Column(name = "last_update_time")
    private LocalDateTime lastUpdateTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
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
