package es.hargos.ritrack.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

/**
 * DTO para mostrar información resumida de riders
 */
@Getter
@Setter
@EqualsAndHashCode(of = "riderId") // Para evitar duplicados en streams
public class RiderSummaryDto {

    private Integer riderId;
    private String name;
    private String phone;
    private String email;
    private String contractType;
    private String status;
    private Integer deliveredOrders;
    private Integer cityId;

    @JsonFormat(pattern = "HH:mm")
    private String activeShiftStartedAt;

    // Constructor completo
    public RiderSummaryDto(Integer riderId, String name, String phone, String email,
                           String contractType, String status,
                           Integer deliveredOrders, String activeShiftStartedAt,
                           Integer cityId) {
        this.riderId = riderId;
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.contractType = contractType;
        this.status = status;
        this.deliveredOrders = deliveredOrders != null ? deliveredOrders : 0;
        this.activeShiftStartedAt = activeShiftStartedAt;
        this.cityId = cityId;
    }

    // Constructor vacío
    public RiderSummaryDto() {
        this.deliveredOrders = 0;
    }

    /**
     * Crea RiderSummaryDto desde datos de Live API
     */
    public static RiderSummaryDto fromLiveApiData(Object riderData, Object employeeData) {
        if (!(riderData instanceof Map)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> riderMap = (Map<String, Object>) riderData;

        // Datos básicos del rider
        Integer employeeId = (Integer) riderMap.get("employee_id");
        String name = (String) riderMap.get("name");
        String phone = (String) riderMap.get("phone_number");
        String email = (String) riderMap.get("email");
        String status = (String) riderMap.get("status");

        // Información de entregas
        Integer deliveredOrders = 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> deliveriesInfo = (Map<String, Object>) riderMap.get("deliveries_info");
        if (deliveriesInfo != null) {
            Integer completed = (Integer) deliveriesInfo.get("completed_deliveries_count");
            deliveredOrders = completed != null ? completed : 0;

            // Si no está trabajando, las entregas son 0
            if ("not_working".equals(status)) {
                deliveredOrders = 0;
            }
        }

        // Hora de inicio de turno
        String activeShiftStartedAt = null;
        String shiftStartTimestamp = (String) riderMap.get("active_shift_started_at");
        if (shiftStartTimestamp != null) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(shiftStartTimestamp, DateTimeFormatter.ISO_DATE_TIME);
                activeShiftStartedAt = dateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
            } catch (Exception e) {
                activeShiftStartedAt = null;
            }
        }

        // Tipo de contrato desde employeeData
        String contractType = null;
        Integer cityId = null;
        if (employeeData instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> empMap = (Map<String, Object>) employeeData;

            cityId = (Integer) empMap.get("city_id");

            @SuppressWarnings("unchecked")
            Map<String, Object> activeContract = (Map<String, Object>) empMap.get("active_contract");
            if (activeContract != null) {
                if (cityId == null) {
                    cityId = (Integer) activeContract.get("city_id");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> contract = (Map<String, Object>) activeContract.get("contract");
                if (contract != null) {
                    contractType = (String) contract.get("type");
                }
            }
        }

        return new RiderSummaryDto(
                employeeId, name, phone, email, contractType, status,
                deliveredOrders, activeShiftStartedAt, cityId);
    }

    /**
     * Crea RiderSummaryDto desde datos de Rooster API
     */
    public static RiderSummaryDto fromRoosterApiData(Object employeeData) {
        if (!(employeeData instanceof Map)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> empMap = (Map<String, Object>) employeeData;

        // Datos básicos
        Integer employeeId = (Integer) empMap.get("id");
        String name = (String) empMap.get("name");
        String phone = (String) empMap.get("phone_number");
        String email = (String) empMap.get("email");
        Integer cityId = (Integer) empMap.get("city_id");

        // Tipo de contrato
        String contractType = null;
        @SuppressWarnings("unchecked")
        Map<String, Object> activeContract = (Map<String, Object>) empMap.get("active_contract");
        if (activeContract != null) {
            if (cityId == null) {
                cityId = (Integer) activeContract.get("city_id");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> contract = (Map<String, Object>) activeContract.get("contract");
            if (contract != null) {
                contractType = (String) contract.get("type");
            }
        }

        return new RiderSummaryDto(
                employeeId, name, phone, email, contractType, "not_working", 0, null, cityId);
    }

    /**
     * Verifica si el rider está activo (trabajando)
     */
    @JsonIgnore
    public boolean isActive() {
        return status != null && !"not_working".equals(status.toLowerCase());
    }

    /**
     * Formatea el tipo de contrato para mostrar
     */
    @JsonIgnore
    public String getContractTypeDisplay() {
        if (contractType == null) {
            return "No especificado";
        }

        return switch (contractType) {
            case "FULL_TIME" -> "Tiempo Completo";
            case "PART_TIME" -> "Tiempo Parcial";
            default -> contractType;
        };
    }

    /**
     * Formatea el estado para mostrar
     */
    @JsonIgnore
    public String getStatusDisplay() {
        if (status == null) {
            return "Desconocido";
        }

        return switch (status.toLowerCase()) {
            case "not_working" -> "No trabajando";
            case "working" -> "Trabajando";
            case "available" -> "Disponible";
            case "break" -> "En descanso";
            case "ready" -> "Listo";
            case "starting" -> "Iniciando";
            case "ending" -> "Finalizando";
            case "late" -> "Tarde";
            case "temp_not_working" -> "Temporalmente no trabajando";
            default -> status;
        };
    }

    /**
     * Información del turno
     */
    @JsonIgnore
    public String getShiftInfo() {
        return activeShiftStartedAt != null ?
                "Iniciado a las " + activeShiftStartedAt :
                "Sin turno activo";
    }

    @Override
    public String toString() {
        return "RiderSummaryDto{" +
                "riderId=" + riderId +
                ", name='" + name + '\'' +
                ", status='" + status + '\'' +
                ", contractType='" + contractType + '\'' +
                ", deliveredOrders=" + deliveredOrders +
                ", activeShiftStartedAt='" + activeShiftStartedAt + '\'' +
                '}';
    }
}