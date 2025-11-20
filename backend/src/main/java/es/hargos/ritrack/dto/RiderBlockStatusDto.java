package es.hargos.ritrack.dto;

import es.hargos.ritrack.entity.RiderBlockStatusEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO para el estado de bloqueo de un rider.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiderBlockStatusDto {

    private String employeeId;
    private Boolean isAutoBlocked;
    private Boolean isManualBlocked;
    private BigDecimal lastBalance;
    private LocalDateTime lastBalanceCheck;
    private LocalDateTime autoBlockedAt;
    private LocalDateTime autoUnblockedAt;
    private LocalDateTime manualBlockedAt;
    private Long manualBlockedByUserId;
    private String manualBlockReason;

    /**
     * Convierte una entidad a DTO.
     */
    public static RiderBlockStatusDto fromEntity(RiderBlockStatusEntity entity) {
        if (entity == null) {
            return null;
        }

        return RiderBlockStatusDto.builder()
                .employeeId(entity.getEmployeeId())
                .isAutoBlocked(entity.getIsAutoBlocked())
                .isManualBlocked(entity.getIsManualBlocked())
                .lastBalance(entity.getLastBalance())
                .lastBalanceCheck(entity.getLastBalanceCheck())
                .autoBlockedAt(entity.getAutoBlockedAt())
                .autoUnblockedAt(entity.getAutoUnblockedAt())
                .manualBlockedAt(entity.getManualBlockedAt())
                .manualBlockedByUserId(entity.getManualBlockedByUserId())
                .manualBlockReason(entity.getManualBlockReason())
                .build();
    }
}
