package es.hargos.ritrack.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * DTO para representar información de ciudades
 */
@Getter
@Setter
public class CityDto {

    private Integer id;
    private String name;
    private String timeZone;
    private Integer totalRiders;
    private Integer activeRiders;
    private Integer ridersWithDeliveries;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastUpdate;

    // Constructor completo
    public CityDto(Integer id, String name, String timeZone) {
        this.id = id;
        this.name = name;
        this.timeZone = timeZone;
        this.totalRiders = 0;
        this.activeRiders = 0;
        this.ridersWithDeliveries = 0;
        this.lastUpdate = LocalDateTime.now();
    }

    // Constructor vacío
    public CityDto() {
        this.lastUpdate = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "CityDto{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", timeZone='" + timeZone + '\'' +
                ", totalRiders=" + totalRiders +
                ", activeRiders=" + activeRiders +
                ", ridersWithDeliveries=" + ridersWithDeliveries +
                ", lastUpdate=" + lastUpdate +
                '}';
    }
}