package es.hargos.ritrack.dto;

import lombok.*;

import java.math.BigDecimal;

/**
 * DTO para configuración del sistema de auto-bloqueo por cash.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutoBlockConfigDto {

    /**
     * Si el sistema de auto-bloqueo está activado para este tenant.
     */
    private Boolean enabled;

    /**
     * Límite de cash en € para bloquear automáticamente.
     * Ejemplo: 150.00 → bloquea cuando balance >= 150€
     */
    private BigDecimal cashLimit;

    /**
     * Margen de desbloqueo calculado (20% del cashLimit).
     * Solo lectura - calculado automáticamente.
     * Ejemplo: cashLimit=150€ → unblockThreshold=120€ (150 - 30)
     */
    private BigDecimal unblockThreshold;

    /**
     * Porcentaje de hysteresis (siempre 20%, para referencia).
     */
    @Builder.Default
    private Integer hysteresisPercent = 20;
}
