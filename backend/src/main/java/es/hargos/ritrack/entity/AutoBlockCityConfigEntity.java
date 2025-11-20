package es.hargos.ritrack.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad que almacena la configuración de auto-bloqueo por ciudad.
 *
 * MULTI-TENANT: Esta entidad vive en el schema de cada tenant.
 *
 * Permite configurar el auto-bloqueo de forma independiente para cada ciudad:
 * - Barcelona: 150€, habilitado
 * - Madrid: 200€, habilitado
 * - Valencia: deshabilitado
 *
 * Si no existe configuración para una ciudad, el auto-bloqueo está DESACTIVADO.
 */
@Entity
@Table(name = "auto_block_city_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutoBlockCityConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "city_id", nullable = false, unique = true)
    private Integer cityId;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = false;

    @Column(name = "cash_limit", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal cashLimit = new BigDecimal("150.00");

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
