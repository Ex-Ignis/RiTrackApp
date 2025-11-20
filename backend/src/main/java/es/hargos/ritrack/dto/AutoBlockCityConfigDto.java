package es.hargos.ritrack.dto;

import es.hargos.ritrack.entity.AutoBlockCityConfigEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO para configuración de auto-bloqueo por ciudad.
 *
 * Permite configurar el auto-bloqueo de forma independiente para cada ciudad:
 * - Barcelona (cityId: 902): enabled=true, cashLimit=150€
 * - Madrid (cityId: 804): enabled=true, cashLimit=200€
 * - Valencia (cityId: 882): enabled=false
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutoBlockCityConfigDto {

    /**
     * ID de la configuración (para updates).
     */
    private Long id;

    /**
     * ID de la ciudad de Glovo.
     */
    private Integer cityId;

    /**
     * Si el auto-bloqueo está activado para esta ciudad.
     */
    private Boolean enabled;

    /**
     * Límite de cash en € para bloquear automáticamente en esta ciudad.
     * Ejemplo: 150.00 → bloquea cuando balance >= 150€
     */
    private BigDecimal cashLimit;

    /**
     * Umbral de desbloqueo calculado (80% del cashLimit).
     * Solo lectura - calculado automáticamente.
     * Ejemplo: cashLimit=150€ → unblockThreshold=120€
     */
    private BigDecimal unblockThreshold;

    /**
     * Porcentaje de hysteresis (siempre 20%).
     */
    @Builder.Default
    private Integer hysteresisPercent = 20;

    /**
     * Fecha de creación.
     */
    private LocalDateTime createdAt;

    /**
     * Fecha de última actualización.
     */
    private LocalDateTime updatedAt;

    /**
     * Convierte una entidad a DTO, calculando el umbral de desbloqueo.
     *
     * @param entity Entidad de configuración
     * @return DTO con datos completos
     */
    public static AutoBlockCityConfigDto fromEntity(AutoBlockCityConfigEntity entity) {
        if (entity == null) {
            return null;
        }

        BigDecimal unblockThreshold = entity.getCashLimit()
                .multiply(new BigDecimal("0.80"))
                .setScale(2, BigDecimal.ROUND_HALF_UP);

        return AutoBlockCityConfigDto.builder()
                .id(entity.getId())
                .cityId(entity.getCityId())
                .enabled(entity.getEnabled())
                .cashLimit(entity.getCashLimit())
                .unblockThreshold(unblockThreshold)
                .hysteresisPercent(20)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Convierte el DTO a entidad (para crear o actualizar).
     *
     * @return Nueva entidad
     */
    public AutoBlockCityConfigEntity toEntity() {
        return AutoBlockCityConfigEntity.builder()
                .id(this.id)
                .cityId(this.cityId)
                .enabled(this.enabled != null ? this.enabled : false)
                .cashLimit(this.cashLimit != null ? this.cashLimit : new BigDecimal("150.00"))
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }
}
