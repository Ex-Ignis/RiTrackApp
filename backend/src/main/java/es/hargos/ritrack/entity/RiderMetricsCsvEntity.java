package es.hargos.ritrack.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Rider performance metrics imported from CSV files.
 * Replaces automatic metrics collection - now metrics are imported manually via CSV.
 *
 * Note: This entity does NOT have a fixed schema. It is created dynamically
 * per tenant schema (e.g., arendel.rider_metrics_csv, entregalia.rider_metrics_csv).
 */
@Entity
@Table(name = "rider_metrics_csv")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderMetricsCsvEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Glovo employee ID (from Rooster API)
     */
    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    /**
     * Metrics date
     */
    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "city_id")
    private Integer cityId;

    // Performance metrics
    @Column(name = "hours_worked", precision = 10, scale = 2)
    private BigDecimal hoursWorked;

    @Column(name = "deliveries_completed")
    private Integer deliveriesCompleted;

    @Column(name = "deliveries_cancelled")
    private Integer deliveriesCancelled;

    @Column(name = "acceptance_rate", precision = 5, scale = 2)
    private BigDecimal acceptanceRate;

    @Column(name = "rejection_rate", precision = 5, scale = 2)
    private BigDecimal rejectionRate;

    @Column(name = "average_rating", precision = 3, scale = 2)
    private BigDecimal averageRating;

    @Column(name = "orders_per_hour", precision = 10, scale = 2)
    private BigDecimal ordersPerHour;

    // Financial metrics
    @Column(precision = 10, scale = 2)
    private BigDecimal earnings;

    @Column(precision = 10, scale = 2)
    private BigDecimal tips;

    // Operational metrics
    @Column(name = "distance_km", precision = 10, scale = 2)
    private BigDecimal distanceKm;

    @Column(name = "utilization_rate", precision = 5, scale = 2)
    private BigDecimal utilizationRate;

    @Column(name = "break_time_minutes")
    private Integer breakTimeMinutes;

    @Column(name = "late_time_minutes")
    private Integer lateTimeMinutes;

    /**
     * Additional metadata stored as JSON (shift times, zones, incidents, etc.)
     */
    @Column(columnDefinition = "JSONB")
    private String metadata;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (importedAt == null) {
            importedAt = LocalDateTime.now();
        }
    }
}
