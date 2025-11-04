package es.hargos.ritrack.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * DTO simplificado para filtros de búsqueda de riders
 */
@Getter
@Setter
@Builder
@ToString
public class RiderFilterDto {

    private String name;               // Filtro por nombre (contiene, case insensitive)
    private Integer riderId;           // Filtro por employee_id específico
    private String phone;              // Filtro por teléfono (contiene)
    private String email;              // Filtro por email (contiene)
    private String status;             // Filtro por estado
    private Integer cityId;            // Filtro por ciudad
    private String contractType;       // Filtro por tipo de contrato (FULL_TIME, PART_TIME, etc.)
    private Boolean hasActiveDelivery; // Filtro por delivery activo
    private Boolean isWorking;         // Filtro por si está trabajando (status != not_working)
    private Integer companyId;         // Filtro por empresa

    // Parámetros de paginación
    @Builder.Default
    private Integer page = 0;          // Página (empezando en 0)

    @Builder.Default
    private Integer size = 10;         // Tamaño de página (siempre 10)

    /**
     * Constructor sin argumentos para compatibilidad
     */
    public RiderFilterDto() {
        this.page = 0;
        this.size = 10;
    }

    /**
     * Constructor completo
     */
    public RiderFilterDto(String name, Integer riderId, String phone, String email, String status,
                          Integer cityId, String contractType, Boolean hasActiveDelivery,
                          Boolean isWorking, Integer companyId, Integer page, Integer size) {
        this.name = name;
        this.riderId = riderId;
        this.phone = phone;
        this.email = email;
        this.status = status;
        this.cityId = cityId;
        this.contractType = contractType;
        this.hasActiveDelivery = hasActiveDelivery;
        this.isWorking = isWorking;
        this.companyId = companyId;
        this.page = page != null ? page : 0;
        this.size = 10;
    }

    /**
     * Valida que la página no sea negativa
     */
    public Integer getPage() {
        return page != null && page >= 0 ? page : 0;
    }

    /**
     * Siempre devuelve 10 como tamaño de página
     */
    public Integer getSize() {
        return 10;
    }

    /**
     * Verifica si hay algún filtro aplicado (excluyendo paginación)
     */
    public boolean hasFilters() {
        return name != null || riderId != null || phone != null || email != null ||
                status != null || cityId != null || contractType != null ||
                hasActiveDelivery != null || isWorking != null || companyId != null;
    }

    /**
     * Crea una descripción legible de los filtros aplicados
     */
    public String getFilterDescription() {
        if (!hasFilters()) {
            return "Sin filtros aplicados";
        }

        StringBuilder desc = new StringBuilder();
        if (name != null) desc.append("Nombre: ").append(name).append("; ");
        if (riderId != null) desc.append("ID: ").append(riderId).append("; ");
        if (phone != null) desc.append("Teléfono: ").append(phone).append("; ");
        if (email != null) desc.append("Email: ").append(email).append("; ");
        if (status != null) desc.append("Estado: ").append(status).append("; ");
        if (cityId != null) desc.append("Ciudad: ").append(cityId).append("; ");
        if (contractType != null) desc.append("Contrato: ").append(contractType).append("; ");
        if (hasActiveDelivery != null) desc.append("Delivery activo: ").append(hasActiveDelivery).append("; ");
        if (isWorking != null) desc.append("Trabajando: ").append(isWorking).append("; ");
        if (companyId != null) desc.append("Empresa: ").append(companyId).append("; ");

        return desc.toString();
    }
}