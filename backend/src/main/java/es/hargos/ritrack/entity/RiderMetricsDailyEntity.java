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
 * Métricas diarias de riders importadas desde archivos CSV.
 * Tabla multi-tenant: cada tenant tiene sus métricas en su propio schema.
 */
@Entity
@Table(name = "rider_metrics_daily")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderMetricsDailyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rider_id", nullable = false)
    private Integer riderId;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "vehicle", length = 50)
    private String vehicle;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "worked_hours", precision = 10, scale = 2)
    private BigDecimal workedHours;

    @Column(name = "total_completed_deliveries")
    private Integer totalCompletedDeliveries;

    @Column(name = "total_assigned")
    private Integer totalAssigned;

    @Column(name = "total_reassigned")
    private Integer totalReassigned;

    @Column(name = "total_cancelled_deliveries")
    private Integer totalCancelledDeliveries;

    @Column(name = "total_cancelled_near_customer")
    private Integer totalCancelledNearCustomer;

    @Column(name = "utr", precision = 10, scale = 2)
    private BigDecimal utr;

    @Column(name = "efficiency", precision = 10, scale = 2)
    private BigDecimal efficiency;

    @Column(name = "total_stacked_deliveries")
    private Integer totalStackedDeliveries;

    @Column(name = "total_stacked_intravendor")
    private Integer totalStackedIntravendor;

    @Column(name = "total_stacked_intervendor")
    private Integer totalStackedIntervendor;

    @Column(name = "driven_distance_km", precision = 10, scale = 2)
    private BigDecimal drivenDistanceKm;

    @Column(name = "total_wtp_min", precision = 10, scale = 2)
    private BigDecimal totalWtpMin;

    @Column(name = "total_wtd_min", precision = 10, scale = 2)
    private BigDecimal totalWtdMin;

    @Column(name = "booked_shifts")
    private Integer bookedShifts;

    @Column(name = "unbooked_shifts")
    private Integer unbookedShifts;

    @Column(name = "balance_eod", length = 50)
    private String balanceEod;

    @Column(name = "total_cdt", precision = 10, scale = 2)
    private BigDecimal totalCdt;

    @Column(name = "avg_cdt", precision = 10, scale = 2)
    private BigDecimal avgCdt;

    @Column(name = "pd_speed_kmh", precision = 10, scale = 2)
    private BigDecimal pdSpeedKmh;

    @Column(name = "tips", precision = 10, scale = 2)
    private BigDecimal tips;

    @Column(name = "imported_at")
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
