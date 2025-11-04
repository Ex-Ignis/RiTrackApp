package es.hargos.ritrack.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO completo con información estática y dinámica de un rider
 */
@Getter
@Setter
@Builder
public class RiderDetailDto {

    // ==========================================
    // DATOS ESTÁTICOS (desde Rooster API)
    // ==========================================

    // Información personal
    private Integer riderId;
    private String name;
    private String dni;
    private String phone;
    private String email;
    private String bankData;
    private String operationalPhoneNumber;
    private String addressCity;
    private String glovoId;
    private String iban;
    private String irpf;
    private String religion;
    private Boolean hasBox;
    private Boolean materialReceived;
    private Boolean mcc;
    private String referralUrl;
    private String shortReferralUrl;

    // Información contractual
    private String contractType;
    private String contractName;
    private Integer companyId;
    private String jobTitle;
    private Integer cityId;
    private String cityName;
    private String timeZone;

    // Vehículos asignados (pueden ser múltiples)
    private List<VehicleTypeInfo> assignedVehicles;

    // Puntos de inicio
    private List<StartingPointInfo> startingPoints;

    // Fechas importantes
    @JsonFormat(pattern = "yyyy-MM-dd")
    private String birthDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private String contractStartDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private String contractEndDate;

    // ==========================================
    // DATOS DINÁMICOS (desde Live API)
    // ==========================================

    // Estado actual
    private String status;
    private String statusReason;
    private String statusPerformedBy;

    // Vehículo en uso actual
    private VehicleInUse currentVehicle;

    // Información de billetera
    private WalletInfo wallet;

    // Información de entregas
    private DeliveriesInfo deliveries;

    // Métricas de rendimiento
    private PerformanceMetrics performance;

    // Información del turno actual
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private String activeShiftStartedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private String activeShiftEndedAt;

    // Ubicación actual
    private LocationInfo currentLocation;

    // Metadata
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastUpdated;

    // Metadata adicional de Rooster
    private Integer batchNumber;
    private Integer reportingTo;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private String createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private String updatedAt;
    private Boolean isDataComplete;
    private String dataSource; // "FULL", "ROOSTER_ONLY", "LIVE_ONLY"

    // ==========================================
    // CLASES INTERNAS PARA ESTRUCTURAS COMPLEJAS
    // ==========================================

    @Getter
    @Setter
    @Builder
    public static class VehicleTypeInfo {
        private Integer vehicleTypeId;
        private String vehicleTypeName;
        private String icon;
        private Boolean active;
    }

    @Getter
    @Setter
    @Builder
    public static class StartingPointInfo {
        private Integer id;
        private String name;
        private Integer cityId;
    }

    @Getter
    @Setter
    @Builder
    public static class VehicleInUse {
        private Integer id;
        private String name;
        private String icon;
        private Double defaultSpeed;
        private String profile;
    }

    @Getter
    @Setter
    @Builder
    public static class WalletInfo {
        private Double balance;
        private String limitStatus; // "balance_under_soft_limit", etc.
    }

    @Getter
    @Setter
    @Builder
    public static class DeliveriesInfo {
        private Boolean hasActiveDelivery;
        private Integer activeDeliveryId;
        private List<String> activeDropoffAddresses; // Lista de direcciones de entrega activas
        private Integer completedDeliveriesCount;
        private Integer cancelledDeliveriesCount;
        private Integer cancelledByIssueService;
        private Integer cancelledByOtherReasons;
        private Integer acceptedDeliveriesCount;
        private Integer totalPickupWaitTime; // Tiempo total de espera en pickup (segundos)
        private Integer totalDropoffWaitTime; // Tiempo total de espera en dropoff (segundos)

        // NUEVO: Método para formatear tiempo de espera
        public String getFormattedPickupWaitTime() {
            return formatWaitTime(totalPickupWaitTime);
        }

        public String getFormattedDropoffWaitTime() {
            return formatWaitTime(totalDropoffWaitTime);
        }

        private String formatWaitTime(Integer seconds) {
            if (seconds == null || seconds == 0) return "0 min";
            int minutes = seconds / 60;
            int secs = seconds % 60;
            return minutes > 0 ? String.format("%d min %d seg", minutes, secs) : String.format("%d seg", secs);
        }
    }

    @Getter
    @Setter
    @Builder
    public static class PerformanceMetrics {
        private Double utilizationRate;
        private Double reassignmentRate;
        private Double acceptanceRate;
        private TimeSpentInfo timeSpent;
    }

    @Getter
    @Setter
    @Builder
    public static class TimeSpentInfo {
        private Integer workedSeconds;
        private Integer lateSeconds;
        private Integer breakSeconds;
        private Integer numberOfBreaks;

        // Métodos de conveniencia para obtener tiempos formateados
        public String getWorkedTime() {
            return formatSeconds(workedSeconds);
        }

        public String getLateTime() {
            return formatSeconds(lateSeconds);
        }

        public String getBreakTime() {
            return formatSeconds(breakSeconds);
        }

        private String formatSeconds(Integer seconds) {
            if (seconds == null || seconds == 0) return "0:00:00";

            int hours = seconds / 3600;
            int minutes = (seconds % 3600) / 60;
            int secs = seconds % 60;

            return String.format("%d:%02d:%02d", hours, minutes, secs);
        }
    }

    @Getter
    @Setter
    @Builder
    public static class LocationInfo {
        private Double latitude;
        private Double longitude;
        private Double accuracy;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private String locationUpdatedAt;
    }

    // ==========================================
    // MÉTODOS DE UTILIDAD
    // ==========================================

    /**
     * Verifica si el rider está activo (trabajando)
     */
    public boolean isActive() {
        return status != null && !"not_working".equalsIgnoreCase(status);
    }

    /**
     * Obtiene descripción del estado formateada
     */
    public String getStatusDescription() {
        if (status == null) return "Desconocido";

        String desc = switch (status.toLowerCase()) {
            case "not_working" -> "No trabajando";
            case "working" -> "Trabajando";
            case "available" -> "Disponible";
            case "break" -> "En descanso";
            case "ready" -> "Listo";
            case "starting" -> "Iniciando turno";
            case "ending" -> "Finalizando turno";
            case "late" -> "Tarde";
            case "temp_not_working" -> "Temporalmente no trabajando";
            default -> status;
        };

        if (statusReason != null && !statusReason.isEmpty()) {
            desc += " - " + statusReason;
        }

        return desc;
    }
}