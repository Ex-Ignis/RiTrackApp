package es.hargos.ritrack.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderMetricsDailyDto {
    private Integer riderId;
    private LocalDate day;
    private String city;
    private String vehicle;
    private String phone;
    private BigDecimal workedHours;
    private Integer totalCompletedDeliveries;
    private Integer totalAssigned;
    private Integer totalReassigned;
    private Integer totalCancelledDeliveries;
    private Integer totalCancelledNearCustomer;
    private BigDecimal utr;
    private BigDecimal efficiency;
    private Integer totalStackedDeliveries;
    private Integer totalStackedIntravendor;
    private Integer totalStackedIntervendor;
    private BigDecimal drivenDistanceKm;
    private BigDecimal totalWtpMin;
    private BigDecimal totalWtdMin;
    private Integer bookedShifts;
    private Integer unbookedShifts;
    private String balanceEod;
    private BigDecimal totalCdt;
    private BigDecimal avgCdt;
    private BigDecimal pdSpeedKmh;
    private BigDecimal tips;

    public static RiderMetricsDailyDto fromEntity(es.hargos.ritrack.entity.RiderMetricsDailyEntity entity) {
        return RiderMetricsDailyDto.builder()
                .riderId(entity.getRiderId())
                .day(entity.getDay())
                .city(entity.getCity())
                .vehicle(entity.getVehicle())
                .phone(entity.getPhone())
                .workedHours(entity.getWorkedHours())
                .totalCompletedDeliveries(entity.getTotalCompletedDeliveries())
                .totalAssigned(entity.getTotalAssigned())
                .totalReassigned(entity.getTotalReassigned())
                .totalCancelledDeliveries(entity.getTotalCancelledDeliveries())
                .totalCancelledNearCustomer(entity.getTotalCancelledNearCustomer())
                .utr(entity.getUtr())
                .efficiency(entity.getEfficiency())
                .totalStackedDeliveries(entity.getTotalStackedDeliveries())
                .totalStackedIntravendor(entity.getTotalStackedIntravendor())
                .totalStackedIntervendor(entity.getTotalStackedIntervendor())
                .drivenDistanceKm(entity.getDrivenDistanceKm())
                .totalWtpMin(entity.getTotalWtpMin())
                .totalWtdMin(entity.getTotalWtdMin())
                .bookedShifts(entity.getBookedShifts())
                .unbookedShifts(entity.getUnbookedShifts())
                .balanceEod(entity.getBalanceEod())
                .totalCdt(entity.getTotalCdt())
                .avgCdt(entity.getAvgCdt())
                .pdSpeedKmh(entity.getPdSpeedKmh())
                .tips(entity.getTips())
                .build();
    }
}