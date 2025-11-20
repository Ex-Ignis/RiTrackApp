package es.hargos.ritrack.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DTO para representar la ubicación y estado de un rider
 */
@Setter
@Getter
public class RiderLocationDto {

    private String employeeId;  // Cambiado a String para compatibilidad con API
    private String fullName;
    private Double latitude;
    private Double longitude;
    private Double accuracy;
    private String updatedAt;  // Renombrado de locationUpdatedAt
    private String status;
    private Boolean hasActiveDelivery;
    private Integer vehicleTypeId;  // Agregado
    private String vehicleTypeName;  // Renombrado de vehicleName
    private String phoneNumber;
    private String operationalPhoneNumber;

    // Wallet info - Para sistema de auto-bloqueo
    private Double walletBalance;
    private String walletLimitStatus;  // "balance_under_soft_limit", etc.

    // Block status
    private Boolean isAutoBlocked;     // Bloqueado automáticamente por saldo alto
    private Boolean isManualBlocked;   // Bloqueado manualmente por admin

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastUpdate;

    // Constructor completo
    public RiderLocationDto(String employeeId, String fullName, Double latitude, Double longitude,
                            Double accuracy, String updatedAt, String status,
                            Boolean hasActiveDelivery, String vehicleTypeName) {
        this.employeeId = employeeId;
        this.fullName = fullName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.updatedAt = updatedAt;
        this.status = status;
        this.hasActiveDelivery = hasActiveDelivery != null ? hasActiveDelivery : false;
        this.vehicleTypeName = vehicleTypeName;
        this.lastUpdate = LocalDateTime.now();
    }

    // Constructor vacío
    public RiderLocationDto() {
        this.lastUpdate = LocalDateTime.now();
    }

    /**
     * Verifica si la ubicación es válida (tiene coordenadas)
     */
    @JsonIgnore
    public boolean isLocationValid() {
        return latitude != null && longitude != null &&
                latitude != 0.0 && longitude != 0.0 &&
                latitude >= -90 && latitude <= 90 &&
                longitude >= -180 && longitude <= 180;
    }

    /**
     * Obtiene el estado del rider con información adicional
     */
    @JsonIgnore
    public String getStatusDisplay() {
        StringBuilder statusBuilder = new StringBuilder();

        if (status != null) {
            statusBuilder.append(status.replace("_", " ").toUpperCase());
        }

        if (hasActiveDelivery != null && hasActiveDelivery) {
            statusBuilder.append(" (En entrega)");
        }

        return statusBuilder.toString();
    }

    /**
     * Formatea la precisión de la ubicación
     */
    @JsonIgnore
    public String getAccuracyDisplay() {
        if (accuracy == null) {
            return "Desconocida";
        }
        return String.format("±%.1f m", accuracy);
    }

    /**
     * Formatea el timestamp de la última actualización de ubicación
     */
    @JsonIgnore
    public String getLocationUpdatedAtDisplay() {
        if (updatedAt == null) {
            return "Desconocida";
        }
        try {
            // Parsear el timestamp ISO y formatearlo
            LocalDateTime dateTime = LocalDateTime.parse(updatedAt, DateTimeFormatter.ISO_DATE_TIME);
            return dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        } catch (Exception e) {
            return updatedAt; // Devolver el valor original si hay error
        }
    }

    @Override
    public String toString() {
        return "RiderLocationDto{" +
                "employeeId='" + employeeId + '\'' +
                ", fullName='" + fullName + '\'' +
                ", latitude=" + latitude +
                ", longitude=" + longitude +
                ", status='" + status + '\'' +
                ", hasActiveDelivery=" + hasActiveDelivery +
                ", vehicleTypeName='" + vehicleTypeName + '\'' +
                ", lastUpdate=" + lastUpdate +
                '}';
    }
}