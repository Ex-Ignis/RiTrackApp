package es.hargos.ritrack.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderMetricsSummary {
    private Integer riderId;
    private String city;
    private String vehicle;
    private String phone;
    private Double workedHours;
    private Integer totalCompletedDeliveries;
    private Integer totalAssigned;
    private Integer totalReassigned;
    private Integer totalCancelledDeliveries;
    private Integer totalCancelledDeliveriesNearCustomer;
    private Double utr;
    private Double efficiency;
    private Integer totalStackedDeliveries;
    private Integer totalStackedDeliveriesIntravendor;
    private Integer totalStackedDeliveriesIntervendor;
    private Double drivenDistanceGoogle;
    private Double totalWTP;
    private Double totalWTD;
    private Integer bookedShifts;
    private Integer unbookedShifts;
    private Double totalCDT;
    private Double avgCDT;
    private Double pdSpeed;
    private Double tips;
}