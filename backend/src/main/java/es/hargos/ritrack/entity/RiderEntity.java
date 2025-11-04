package es.hargos.ritrack.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;

/**
 * Rider entity - Employee data synced from Glovo Rooster and Live APIs.
 *
 * Note: This entity does NOT have a fixed schema. It is created dynamically
 * per tenant schema (e.g., arendel.riders, entregalia.riders).
 *
 * Data sources:
 * - Glovo Rooster API: employee_id, full_name, email, phone, city_id, contract_type, vehicle_type
 * - Glovo Live API: status, current_location
 */
@Entity
@Table(name = "riders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Glovo employee ID (from Rooster API)
     * This is the primary identifier for riders in Glovo's system
     */
    @Column(name = "employee_id", nullable = false, unique = true)
    private String employeeId;

    /**
     * Full name of the rider
     */
    @Column(name = "full_name", nullable = false)
    private String fullName;

    /**
     * Email address (unique per tenant schema)
     */
    @Column(nullable = false, unique = true)
    private String email;

    /**
     * Phone number with country code (e.g., +34612345678)
     */
    private String phone;

    /**
     * Glovo city ID (e.g., 902=Madrid, 804=Barcelona)
     */
    @Column(name = "city_id", nullable = false)
    private Integer cityId;

    /**
     * Human-readable city name (cached from Glovo API)
     */
    @Column(name = "city_name")
    private String cityName;

    /**
     * Contract type: FULL_TIME, PART_TIME, etc.
     */
    @Column(name = "contract_type")
    private String contractType;

    /**
     * Current rider status from Glovo Live API:
     * NOT_WORKING, AVAILABLE, STARTING, READY, WORKING, BREAK,
     * TEMP_NOT_WORKING, ENDING, LATE
     */
    private String status;

    /**
     * Glovo vehicle type ID (1=Car, 3=Motorbike, 5=Bike, etc.)
     */
    @Column(name = "vehicle_type")
    private Integer vehicleType;

    /**
     * Human-readable vehicle type name (cached from Glovo API)
     */
    @Column(name = "vehicle_type_name")
    private String vehicleTypeName;

    /**
     * Starting point ID (distribution center)
     */
    @Column(name = "starting_point_id")
    private Integer startingPointId;

    /**
     * Starting point name (cached from Glovo API)
     */
    @Column(name = "starting_point_name")
    private String startingPointName;

    /**
     * DNI / ID number (from custom fields in Rooster API)
     */
    @Column(name = "dni")
    private String dni;

    /**
     * Timestamp of last sync with Glovo API
     */
    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

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