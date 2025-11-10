package es.hargos.ritrack.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for starting points
 * Represents a physical location where riders can start their shifts
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartingPointDto {
    private Integer id;
    private String name;
    private Integer cityId;
}
