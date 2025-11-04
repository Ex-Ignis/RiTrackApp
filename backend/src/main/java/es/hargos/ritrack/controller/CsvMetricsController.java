package es.hargos.ritrack.controller;

import es.hargos.ritrack.dto.PaginatedResponseDto;
import es.hargos.ritrack.dto.RiderMetricsDailyDto;
import es.hargos.ritrack.dto.RiderMetricsSummary;
import es.hargos.ritrack.dto.RiderMetricsWeeklyDto;
import es.hargos.ritrack.entity.RiderMetricsDailyEntity;
import es.hargos.ritrack.entity.RiderMetricsWeeklyEntity;
import es.hargos.ritrack.repository.RiderMetricsDailyRepository;
import es.hargos.ritrack.repository.RiderMetricsWeeklyRepository;
import es.hargos.ritrack.service.CsvMetricsProcessorService;
import es.hargos.ritrack.service.MetricsQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/metrics")
public class CsvMetricsController {

    private static final Logger logger = LoggerFactory.getLogger(CsvMetricsController.class);
    private final CsvMetricsProcessorService csvProcessor;
    private final MetricsQueryService metricsQueryService;
    private final RiderMetricsDailyRepository dailyMetricsRepository;
    private final RiderMetricsWeeklyRepository weeklyMetricsRepository;

    public CsvMetricsController(CsvMetricsProcessorService csvProcessor,
                                MetricsQueryService metricsQueryService,
                                RiderMetricsDailyRepository dailyMetricsRepository,
                                RiderMetricsWeeklyRepository weeklyMetricsRepository) {
        this.csvProcessor = csvProcessor;
        this.metricsQueryService = metricsQueryService;
        this.dailyMetricsRepository = dailyMetricsRepository;
        this.weeklyMetricsRepository = weeklyMetricsRepository;
    }

    @PostMapping("/upload/daily")
    public ResponseEntity<?> uploadDailyMetrics(@RequestParam("file") MultipartFile file) {
        logger.info("Recibida solicitud de carga de métricas diarias");

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El archivo está vacío"));
            }

            if (!file.getOriginalFilename().toLowerCase().endsWith(".csv")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El archivo debe ser CSV"));
            }

            Map<String, Object> result = csvProcessor.processDailyCsv(file);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Métricas diarias procesadas exitosamente");
            response.put("result", result);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // Errores de validación del formato
            logger.warn("Validación fallida: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error", "Validación de formato fallida",
                            "message", e.getMessage(),
                            "suggestion", "Verifica que estés subiendo el archivo correcto al endpoint adecuado"
                    ));

        } catch (Exception e) {
            logger.error("Error procesando CSV diario: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "Error procesando archivo",
                            "message", e.getMessage()
                    ));
        }
    }

    @PostMapping("/upload/weekly")
    public ResponseEntity<?> uploadWeeklyMetrics(@RequestParam("file") MultipartFile file) {
        logger.info("Recibida solicitud de carga de métricas semanales");

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El archivo está vacío"));
            }

            if (!file.getOriginalFilename().toLowerCase().endsWith(".csv")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El archivo debe ser CSV"));
            }

            Map<String, Object> result = csvProcessor.processWeeklyCsv(file);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Métricas semanales procesadas exitosamente");
            response.put("result", result);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // Errores de validación del formato
            logger.warn("Validación fallida: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error", "Validación de formato fallida",
                            "message", e.getMessage(),
                            "suggestion", "Verifica que estés subiendo el archivo correcto al endpoint adecuado"
                    ));

        } catch (Exception e) {
            logger.error("Error procesando CSV semanal: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "Error procesando archivo",
                            "message", e.getMessage()
                    ));
        }
    }

    /**################################################################################################################
     * CONSULTAS DAILY
     * ################################################################################################################
     */

    //Datos diarios de rider en día específico
    @GetMapping("/daily/rider/{riderId}/day/{day}")
    public ResponseEntity<?> getDailyMetricByRiderAndDay(
            @PathVariable Integer riderId,
            @PathVariable String day) {
        try {
            LocalDate date = LocalDate.parse(day);
            RiderMetricsDailyDto metric = metricsQueryService.getDailyMetricByRiderAndDay(riderId, date);

            if (metric == null) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(metric);
        } catch (Exception e) {
            logger.error("Error obteniendo métrica: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Formato de fecha inválido. Usar: YYYY-MM-DD"));
        }
    }
    //Datos totales por cada rider en intervalo (PAGINATED)
    @GetMapping("/daily/date-range")
    public ResponseEntity<?> getDailyMetricsByDateRange(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            List<RiderMetricsSummary> allSummaries = getDailyMetricsSummary(start, end);
            PaginatedResponseDto<RiderMetricsSummary> paginatedResponse =
                    PaginatedResponseDto.of(allSummaries, page, size);

            return ResponseEntity.ok(paginatedResponse);
        } catch (Exception e) {
            logger.error("Error obteniendo métricas agregadas: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Formato de fecha inválido. Usar: YYYY-MM-DD"));
        }
    }

    private List<RiderMetricsSummary> getDailyMetricsSummary(LocalDate startDate, LocalDate endDate) {
        List<RiderMetricsDailyEntity> dailyMetrics = dailyMetricsRepository.findByDayBetween(startDate, endDate);

        Map<Integer, List<RiderMetricsDailyEntity>> metricsByRider = dailyMetrics.stream()
                .collect(Collectors.groupingBy(RiderMetricsDailyEntity::getRiderId));

        return metricsByRider.entrySet().stream()
                .map(entry -> {
                    Integer riderId = entry.getKey();
                    List<RiderMetricsDailyEntity> riderMetrics = entry.getValue();

                    RiderMetricsSummary summary = new RiderMetricsSummary();
                    summary.setRiderId(riderId);
                    summary.setCity(riderMetrics.get(0).getCity());
                    summary.setVehicle(riderMetrics.get(0).getVehicle());
                    summary.setPhone(riderMetrics.get(0).getPhone());

                    // Sumatorios con BigDecimal convertido a Double
                    summary.setWorkedHours(riderMetrics.stream()
                            .map(RiderMetricsDailyEntity::getWorkedHours)
                            .filter(v -> v != null)
                            .mapToDouble(BigDecimal::doubleValue)
                            .sum());

                    summary.setTotalCompletedDeliveries(riderMetrics.stream()
                            .mapToInt(m -> m.getTotalCompletedDeliveries() != null ? m.getTotalCompletedDeliveries() : 0)
                            .sum());

                    summary.setTotalAssigned(riderMetrics.stream()
                            .mapToInt(m -> m.getTotalAssigned() != null ? m.getTotalAssigned() : 0)
                            .sum());

                    summary.setTotalReassigned(riderMetrics.stream()
                            .mapToInt(m -> m.getTotalReassigned() != null ? m.getTotalReassigned() : 0)
                            .sum());

                    summary.setTotalCancelledDeliveries(riderMetrics.stream()
                            .mapToInt(m -> m.getTotalCancelledDeliveries() != null ? m.getTotalCancelledDeliveries() : 0)
                            .sum());

                    summary.setTotalCancelledDeliveriesNearCustomer(riderMetrics.stream()
                            .mapToInt(m -> m.getTotalCancelledNearCustomer() != null ? m.getTotalCancelledNearCustomer() : 0)
                            .sum());

                    summary.setTotalStackedDeliveries(riderMetrics.stream()
                            .mapToInt(m -> m.getTotalStackedDeliveries() != null ? m.getTotalStackedDeliveries() : 0)
                            .sum());

                    summary.setTotalStackedDeliveriesIntravendor(riderMetrics.stream()
                            .mapToInt(m -> m.getTotalStackedIntravendor() != null ? m.getTotalStackedIntravendor() : 0)
                            .sum());

                    summary.setTotalStackedDeliveriesIntervendor(riderMetrics.stream()
                            .mapToInt(m -> m.getTotalStackedIntervendor() != null ? m.getTotalStackedIntervendor() : 0)
                            .sum());

                    summary.setDrivenDistanceGoogle(riderMetrics.stream()
                            .map(RiderMetricsDailyEntity::getDrivenDistanceKm)
                            .filter(v -> v != null)
                            .mapToDouble(BigDecimal::doubleValue)
                            .sum());

                    summary.setTotalWTP(riderMetrics.stream()
                            .map(RiderMetricsDailyEntity::getTotalWtpMin)
                            .filter(v -> v != null)
                            .mapToDouble(BigDecimal::doubleValue)
                            .sum());

                    summary.setTotalWTD(riderMetrics.stream()
                            .map(RiderMetricsDailyEntity::getTotalWtdMin)
                            .filter(v -> v != null)
                            .mapToDouble(BigDecimal::doubleValue)
                            .sum());

                    summary.setBookedShifts(riderMetrics.stream()
                            .mapToInt(m -> m.getBookedShifts() != null ? m.getBookedShifts() : 0)
                            .sum());

                    summary.setUnbookedShifts(riderMetrics.stream()
                            .mapToInt(m -> m.getUnbookedShifts() != null ? m.getUnbookedShifts() : 0)
                            .sum());

                    // totalCdt y avgCdt son BigDecimal, convertir a Double
                    summary.setTotalCDT(riderMetrics.stream()
                            .map(RiderMetricsDailyEntity::getTotalCdt)
                            .filter(v -> v != null)
                            .mapToDouble(BigDecimal::doubleValue)
                            .sum());

                    summary.setTips(riderMetrics.stream()
                            .map(RiderMetricsDailyEntity::getTips)
                            .filter(v -> v != null)
                            .mapToDouble(BigDecimal::doubleValue)
                            .sum());

                    // Promedios - UTR es BigDecimal, convertir a Double
                    summary.setUtr(riderMetrics.stream()
                            .map(RiderMetricsDailyEntity::getUtr)
                            .filter(v -> v != null)
                            .mapToDouble(BigDecimal::doubleValue)
                            .average()
                            .orElse(0.0));

                    summary.setEfficiency(riderMetrics.stream()
                            .map(RiderMetricsDailyEntity::getEfficiency)
                            .filter(v -> v != null)
                            .mapToDouble(BigDecimal::doubleValue)
                            .average()
                            .orElse(0.0));

                    summary.setPdSpeed(riderMetrics.stream()
                            .map(RiderMetricsDailyEntity::getPdSpeedKmh)
                            .filter(v -> v != null)
                            .mapToDouble(BigDecimal::doubleValue)
                            .average()
                            .orElse(0.0));

                    // AVG CDT recalculado
                    if (summary.getTotalCompletedDeliveries() > 0) {
                        summary.setAvgCDT(summary.getTotalCDT() / summary.getTotalCompletedDeliveries());
                    } else {
                        summary.setAvgCDT(0.0);
                    }

                    return summary;
                })
                .collect(Collectors.toList());
    }

    //Datos diarios del rider en intervalo (PAGINATED)
    @GetMapping("/daily/rider/{riderId}/date-range")
    public ResponseEntity<?> getDailyMetricsByRiderAndDateRange(
            @PathVariable Integer riderId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            List<RiderMetricsDailyDto> allMetrics =
                    metricsQueryService.getDailyMetricsByRiderAndDateRange(riderId, start, end);

            PaginatedResponseDto<RiderMetricsDailyDto> paginatedResponse =
                    PaginatedResponseDto.of(allMetrics, page, size);

            return ResponseEntity.ok(paginatedResponse);
        } catch (Exception e) {
            logger.error("Error obteniendo métricas: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Formato de fecha inválido. Usar: YYYY-MM-DD"));
        }
    }

    //Datos totales del rider en intervalo
    @GetMapping("/daily/rider/{riderId}/summary")
    public ResponseEntity<?> getDailyMetricsSummaryByRider(
            @PathVariable Integer riderId,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            RiderMetricsSummary summary = getSingleRiderDailySummary(riderId, start, end);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            logger.error("Error obteniendo resumen de rider: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Formato de fecha inválido. Usar: YYYY-MM-DD"));
        }
    }

    private RiderMetricsSummary getSingleRiderDailySummary(Integer riderId, LocalDate startDate, LocalDate endDate) {
        List<RiderMetricsDailyEntity> riderMetrics = dailyMetricsRepository.findByRiderIdAndDayBetween(riderId, startDate, endDate);

        if (riderMetrics.isEmpty()) {
            return null;
        }

        RiderMetricsSummary summary = new RiderMetricsSummary();
        summary.setRiderId(riderId);
        summary.setCity(riderMetrics.get(0).getCity());
        summary.setVehicle(riderMetrics.get(0).getVehicle());
        summary.setPhone(riderMetrics.get(0).getPhone());

        summary.setWorkedHours(riderMetrics.stream()
                .map(RiderMetricsDailyEntity::getWorkedHours)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setTotalCompletedDeliveries(riderMetrics.stream()
                .mapToInt(m -> m.getTotalCompletedDeliveries() != null ? m.getTotalCompletedDeliveries() : 0)
                .sum());

        summary.setTotalAssigned(riderMetrics.stream()
                .mapToInt(m -> m.getTotalAssigned() != null ? m.getTotalAssigned() : 0)
                .sum());

        summary.setTotalReassigned(riderMetrics.stream()
                .mapToInt(m -> m.getTotalReassigned() != null ? m.getTotalReassigned() : 0)
                .sum());

        summary.setTotalCancelledDeliveries(riderMetrics.stream()
                .mapToInt(m -> m.getTotalCancelledDeliveries() != null ? m.getTotalCancelledDeliveries() : 0)
                .sum());

        summary.setTotalCancelledDeliveriesNearCustomer(riderMetrics.stream()
                .mapToInt(m -> m.getTotalCancelledNearCustomer() != null ? m.getTotalCancelledNearCustomer() : 0)
                .sum());

        summary.setTotalStackedDeliveries(riderMetrics.stream()
                .mapToInt(m -> m.getTotalStackedDeliveries() != null ? m.getTotalStackedDeliveries() : 0)
                .sum());

        summary.setTotalStackedDeliveriesIntravendor(riderMetrics.stream()
                .mapToInt(m -> m.getTotalStackedIntravendor() != null ? m.getTotalStackedIntravendor() : 0)
                .sum());

        summary.setTotalStackedDeliveriesIntervendor(riderMetrics.stream()
                .mapToInt(m -> m.getTotalStackedIntervendor() != null ? m.getTotalStackedIntervendor() : 0)
                .sum());

        summary.setDrivenDistanceGoogle(riderMetrics.stream()
                .map(RiderMetricsDailyEntity::getDrivenDistanceKm)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setTotalWTP(riderMetrics.stream()
                .map(RiderMetricsDailyEntity::getTotalWtpMin)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setTotalWTD(riderMetrics.stream()
                .map(RiderMetricsDailyEntity::getTotalWtdMin)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setBookedShifts(riderMetrics.stream()
                .mapToInt(m -> m.getBookedShifts() != null ? m.getBookedShifts() : 0)
                .sum());

        summary.setUnbookedShifts(riderMetrics.stream()
                .mapToInt(m -> m.getUnbookedShifts() != null ? m.getUnbookedShifts() : 0)
                .sum());

        summary.setTotalCDT(riderMetrics.stream()
                .map(RiderMetricsDailyEntity::getTotalCdt)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setTips(riderMetrics.stream()
                .map(RiderMetricsDailyEntity::getTips)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setUtr(riderMetrics.stream()
                .map(RiderMetricsDailyEntity::getUtr)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0));

        summary.setEfficiency(riderMetrics.stream()
                .map(RiderMetricsDailyEntity::getEfficiency)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0));

        summary.setPdSpeed(riderMetrics.stream()
                .map(RiderMetricsDailyEntity::getPdSpeedKmh)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0));

        if (summary.getTotalCompletedDeliveries() > 0) {
            summary.setAvgCDT(summary.getTotalCDT() / summary.getTotalCompletedDeliveries());
        } else {
            summary.setAvgCDT(0.0);
        }

        return summary;
    }



    /**################################################################################################################
     * CONSULTAS WEEKLY
     * ################################################################################################################
     */

    //Datos del rider en esa semana
    @GetMapping("/weekly/rider/{riderId}/week/{week}")
    public ResponseEntity<?> getWeeklyMetricByRiderAndWeek(
            @PathVariable Integer riderId,
            @PathVariable String week) {
        try {
            String weekDate = convertWeekToDate(week);
            RiderMetricsWeeklyDto metric =
                    metricsQueryService.getWeeklyMetricByRiderAndWeek(riderId, weekDate); // ← CAMBIO

            if (metric == null) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(metric);
        } catch (Exception e) {
            logger.error("Error obteniendo métrica: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private String convertWeekToDate(String isoWeek) {
        // Parsear "2025-W40" y obtener la fecha del lunes de esa semana
        String[] parts = isoWeek.split("-W");
        int year = Integer.parseInt(parts[0]);
        int weekNum = Integer.parseInt(parts[1]);

        // Calcular fecha (ajusta el formato según tu BD)
        LocalDate date = LocalDate.of(year, 1, 1)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, weekNum)
                .with(DayOfWeek.MONDAY);

        // Formato exacto: "22 sept 2025" (sin mayúsculas, sin puntos)
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM yyyy", new Locale("es", "ES"));
        return date.format(formatter).replace(".", ""); // Por si acaso hay puntos
    }

    //Datos totales por cada rider en esa semana (PAGINATED)
    @GetMapping("/weekly/week/{week}")
    public ResponseEntity<?> getWeeklyMetricsByWeek(
            @PathVariable String week,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        try {
            String weekDate = convertWeekToDate(week);
            List<RiderMetricsWeeklyDto> allMetrics =
                    metricsQueryService.getWeeklyMetricsByWeek(weekDate);

            PaginatedResponseDto<RiderMetricsWeeklyDto> paginatedResponse =
                    PaginatedResponseDto.of(allMetrics, page, size);

            return ResponseEntity.ok(paginatedResponse);
        } catch (Exception e) {
            logger.error("Error obteniendo métricas de semana {}: {}", week, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    //Datos totales del rider en intervalo de semanas
    @GetMapping("/weekly/rider/{riderId}/summary")
    public ResponseEntity<?> getWeeklyMetricsSummaryByRider(
            @PathVariable Integer riderId,
            @RequestParam String startWeek,
            @RequestParam String endWeek) {
        try {
            String startWeekDate = convertWeekToDate(startWeek);
            String endWeekDate = convertWeekToDate(endWeek);

            RiderMetricsSummary summary = getSingleRiderWeeklySummary(riderId, startWeekDate, endWeekDate);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            logger.error("Error obteniendo resumen semanal: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Formato inválido. Usar: YYYY-Wnn"));
        }
    }

    //Datos semanales del rider en intervalo (PAGINATED)
    @GetMapping("/weekly/rider/{riderId}/weeks")
    public ResponseEntity<?> getWeeklyMetricsByRiderAndWeekRange(
            @PathVariable Integer riderId,
            @RequestParam String startWeek,
            @RequestParam String endWeek,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        try {
            String startWeekDate = convertWeekToDate(startWeek);
            String endWeekDate = convertWeekToDate(endWeek);

            List<RiderMetricsWeeklyDto> allMetrics =
                    metricsQueryService.getWeeklyMetricsByRiderAndWeekRange(riderId, startWeekDate, endWeekDate);

            PaginatedResponseDto<RiderMetricsWeeklyDto> paginatedResponse =
                    PaginatedResponseDto.of(allMetrics, page, size);

            return ResponseEntity.ok(paginatedResponse);
        } catch (Exception e) {
            logger.error("Error obteniendo métricas semanales: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Formato inválido. Usar: YYYY-Wnn"));
        }
    }

    private RiderMetricsSummary getSingleRiderWeeklySummary(Integer riderId, String startWeek, String endWeek) {
        List<RiderMetricsWeeklyEntity> weeklyMetrics = weeklyMetricsRepository.findByRiderIdAndWeekBetween(riderId, startWeek, endWeek);

        if (weeklyMetrics.isEmpty()) {
            return null;
        }

        RiderMetricsSummary summary = new RiderMetricsSummary();
        summary.setRiderId(riderId);
        summary.setCity(weeklyMetrics.get(0).getCity());
        summary.setVehicle(weeklyMetrics.get(0).getVehicle());
        summary.setPhone(weeklyMetrics.get(0).getPhone());

        summary.setWorkedHours(weeklyMetrics.stream()
                .map(RiderMetricsWeeklyEntity::getWorkedHours)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setTotalCompletedDeliveries(weeklyMetrics.stream()
                .mapToInt(m -> m.getTotalCompletedDeliveries() != null ? m.getTotalCompletedDeliveries() : 0)
                .sum());

        summary.setTotalAssigned(weeklyMetrics.stream()
                .mapToInt(m -> m.getTotalAssigned() != null ? m.getTotalAssigned() : 0)
                .sum());

        summary.setTotalReassigned(weeklyMetrics.stream()
                .mapToInt(m -> m.getTotalReassigned() != null ? m.getTotalReassigned() : 0)
                .sum());

        summary.setTotalCancelledDeliveries(weeklyMetrics.stream()
                .mapToInt(m -> m.getTotalCancelledDeliveries() != null ? m.getTotalCancelledDeliveries() : 0)
                .sum());

        summary.setTotalCancelledDeliveriesNearCustomer(weeklyMetrics.stream()
                .mapToInt(m -> m.getTotalCancelledNearCustomer() != null ? m.getTotalCancelledNearCustomer() : 0)
                .sum());

        summary.setTotalStackedDeliveries(weeklyMetrics.stream()
                .mapToInt(m -> m.getTotalStackedDeliveries() != null ? m.getTotalStackedDeliveries() : 0)
                .sum());

        summary.setTotalStackedDeliveriesIntravendor(weeklyMetrics.stream()
                .mapToInt(m -> m.getTotalStackedIntravendor() != null ? m.getTotalStackedIntravendor() : 0)
                .sum());

        summary.setTotalStackedDeliveriesIntervendor(weeklyMetrics.stream()
                .mapToInt(m -> m.getTotalStackedIntervendor() != null ? m.getTotalStackedIntervendor() : 0)
                .sum());

        summary.setDrivenDistanceGoogle(weeklyMetrics.stream()
                .map(RiderMetricsWeeklyEntity::getDrivenDistanceKm)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setTotalWTP(weeklyMetrics.stream()
                .map(RiderMetricsWeeklyEntity::getTotalWtpMin)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setTotalWTD(weeklyMetrics.stream()
                .map(RiderMetricsWeeklyEntity::getTotalWtdMin)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setBookedShifts(weeklyMetrics.stream()
                .mapToInt(m -> m.getBookedShifts() != null ? m.getBookedShifts() : 0)
                .sum());

        summary.setUnbookedShifts(weeklyMetrics.stream()
                .mapToInt(m -> m.getUnbookedShifts() != null ? m.getUnbookedShifts() : 0)
                .sum());

        summary.setTotalCDT(weeklyMetrics.stream()
                .mapToDouble(m -> m.getTotalCdt() != null ? m.getTotalCdt().doubleValue() : 0.0)
                .sum());

        summary.setTips(weeklyMetrics.stream()
                .map(RiderMetricsWeeklyEntity::getTips)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum());

        summary.setUtr(weeklyMetrics.stream()
                .mapToDouble(m -> m.getUtr() != null ? m.getUtr().doubleValue() : 0.0)
                .average()
                .orElse(0.0));

        summary.setEfficiency(weeklyMetrics.stream()
                .map(RiderMetricsWeeklyEntity::getEfficiency)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0));

        summary.setPdSpeed(weeklyMetrics.stream()
                .map(RiderMetricsWeeklyEntity::getPdSpeedKmh)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0));

        if (summary.getTotalCompletedDeliveries() > 0) {
            summary.setAvgCDT(summary.getTotalCDT() / summary.getTotalCompletedDeliveries());
        } else {
            summary.setAvgCDT(0.0);
        }

        return summary;
    }
}