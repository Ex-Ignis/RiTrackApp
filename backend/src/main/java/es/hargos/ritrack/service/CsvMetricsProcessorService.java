package es.hargos.ritrack.service;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import es.hargos.ritrack.entity.RiderMetricsDailyEntity;
import es.hargos.ritrack.entity.RiderMetricsWeeklyEntity;
import es.hargos.ritrack.repository.RiderMetricsDailyRepository;
import es.hargos.ritrack.repository.RiderMetricsWeeklyRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CsvMetricsProcessorService {

    private static final Logger logger = LoggerFactory.getLogger(CsvMetricsProcessorService.class);

    private final RiderMetricsDailyRepository dailyRepository;
    private final RiderMetricsWeeklyRepository weeklyRepository;
    private final EntityManager entityManager;

    public CsvMetricsProcessorService(RiderMetricsDailyRepository dailyRepository,
                                      RiderMetricsWeeklyRepository weeklyRepository,
                                      EntityManager entityManager) {
        this.dailyRepository = dailyRepository;
        this.weeklyRepository = weeklyRepository;
        this.entityManager = entityManager;
    }

    @Transactional(noRollbackFor = {IllegalArgumentException.class})
    public Map<String, Object> processDailyCsv(MultipartFile file) throws Exception {
        logger.info("Procesando CSV Daily: {}", file.getOriginalFilename());

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            List<String[]> rows = reader.readAll();

            if (rows.isEmpty()) {
                throw new IllegalArgumentException("El archivo CSV está vacío");
            }

            String[] headers = rows.get(0);
            validateDailyCsvFormat(headers);
            Map<String, Integer> headerMap = mapHeaders(headers);

            int created = 0;
            int updated = 0;
            int errors = 0;
            List<String> errorDetails = new ArrayList<>();

            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);
                try {
                    // Validación previa de estructura de la fila
                    validateRowStructure(row, headerMap, i + 1);

                    RiderMetricsDailyEntity metric = parseDailyRow(row, headerMap);

                    Optional<RiderMetricsDailyEntity> existing =
                            dailyRepository.findByRiderIdAndDay(metric.getRiderId(), metric.getDay());

                    if (existing.isPresent()) {
                        metric.setId(existing.get().getId());
                        metric.setCreatedAt(existing.get().getCreatedAt());
                        updated++;
                    } else {
                        created++;
                    }

                    dailyRepository.save(metric);

                } catch (IllegalArgumentException e) {
                    String errorMsg = String.format("Fila %d: %s", i + 1, e.getMessage());
                    logger.warn(errorMsg);
                    errorDetails.add(errorMsg);
                    errors++;
                    // Limpiar contexto de persistencia en lugar de flush
                    entityManager.clear();

                } catch (Exception e) {
                    String errorMsg = String.format("Fila %d: Error inesperado - %s", i + 1, e.getMessage());
                    logger.error(errorMsg, e);
                    errorDetails.add(errorMsg);
                    errors++;
                    // Limpiar contexto de persistencia en lugar de flush
                    entityManager.clear();
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("created", created);
            result.put("updated", updated);
            result.put("errors", errors);
            result.put("total_rows", rows.size() - 1);

            // Incluir detalles de errores (máximo 50 para no saturar respuesta)
            if (!errorDetails.isEmpty()) {
                result.put("error_details", errorDetails.stream()
                        .limit(50)
                        .collect(Collectors.toList()));

                if (errorDetails.size() > 50) {
                    result.put("error_note", "Mostrando solo los primeros 50 errores de " + errorDetails.size());
                }
            }

            logger.info("CSV Daily procesado - Creados: {}, Actualizados: {}, Errores: {}",
                    created, updated, errors);

            return result;

        } catch (IllegalArgumentException e) {
            logger.error("Error de validación: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error procesando CSV Daily: {}", e.getMessage());
            throw new RuntimeException("Error procesando archivo: " + e.getMessage());
        }
    }

    @Transactional(noRollbackFor = {IllegalArgumentException.class})
    public Map<String, Object> processWeeklyCsv(MultipartFile file) throws Exception {
        logger.info("Procesando CSV Weekly: {}", file.getOriginalFilename());

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            List<String[]> rows = reader.readAll();

            if (rows.isEmpty()) {
                throw new IllegalArgumentException("El archivo CSV está vacío");
            }

            String[] headers = rows.get(0);

            validateWeeklyCsvFormat(headers);

            Map<String, Integer> headerMap = mapHeaders(headers);

            int created = 0;
            int updated = 0;
            int errors = 0;
            List<String> errorDetails = new ArrayList<>();

            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);
                try {
                    // Validación previa de estructura de la fila
                    validateRowStructureWeekly(row, headerMap, i + 1);

                    RiderMetricsWeeklyEntity metric = parseWeeklyRow(row, headerMap);

                    Optional<RiderMetricsWeeklyEntity> existing =
                            weeklyRepository.findByRiderIdAndWeek(metric.getRiderId(), metric.getWeek());

                    if (existing.isPresent()) {
                        metric.setId(existing.get().getId());
                        metric.setCreatedAt(existing.get().getCreatedAt());
                        updated++;
                    } else {
                        created++;
                    }

                    weeklyRepository.save(metric);

                } catch (IllegalArgumentException e) {
                    String errorMsg = String.format("Fila %d: %s", i + 1, e.getMessage());
                    logger.warn(errorMsg);
                    errorDetails.add(errorMsg);
                    errors++;
                    // Limpiar contexto de persistencia en lugar de flush
                    entityManager.clear();

                } catch (Exception e) {
                    String errorMsg = String.format("Fila %d: Error inesperado - %s", i + 1, e.getMessage());
                    logger.error(errorMsg, e);
                    errorDetails.add(errorMsg);
                    errors++;
                    // Limpiar contexto de persistencia en lugar de flush
                    entityManager.clear();
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("created", created);
            result.put("updated", updated);
            result.put("errors", errors);
            result.put("total_rows", rows.size() - 1);

            // Incluir detalles de errores (máximo 50)
            if (!errorDetails.isEmpty()) {
                result.put("error_details", errorDetails.stream()
                        .limit(50)
                        .collect(Collectors.toList()));

                if (errorDetails.size() > 50) {
                    result.put("error_note", "Mostrando solo los primeros 50 errores de " + errorDetails.size());
                }
            }

            logger.info("CSV Weekly procesado - Creados: {}, Actualizados: {}, Errores: {}",
                    created, updated, errors);

            return result;

        } catch (IllegalArgumentException e) {
            // Re-lanzar errores de validación para que lleguen al controller
            logger.error("Error de validación: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error procesando CSV Weekly: {}", e.getMessage());
            throw new RuntimeException("Error procesando archivo: " + e.getMessage());
        }
    }

    /**
     * Valida que el CSV sea del tipo DAILY
     */
    private void validateDailyCsvFormat(String[] headers) throws IllegalArgumentException {
        List<String> headerList = Arrays.asList(headers);

        // Columna que DEBE existir en Daily pero NO en Weekly
        boolean hasDay = headerList.stream()
                .anyMatch(h -> h.trim().equalsIgnoreCase("Day"));

        // Columna que NO debe existir en Daily (solo en Weekly)
        boolean hasWeek = headerList.stream()
                .anyMatch(h -> h.trim().equalsIgnoreCase("Week"));

        if (!hasDay && hasWeek) {
            throw new IllegalArgumentException(
                    "ERROR: Archivo incorrecto. Has subido un CSV SEMANAL al endpoint de DIARIOS. " +
                            "Por favor, usa el endpoint /api/v1/metrics/upload/weekly"
            );
        }

        if (!hasDay) {
            throw new IllegalArgumentException(
                    "ERROR: El CSV no contiene la columna 'Day'. Verifica que sea un archivo de métricas diarias válido."
            );
        }

        // Validar columnas mínimas requeridas
        String[] requiredColumns = {"Rider ID", "Day", "City"};
        for (String required : requiredColumns) {
            if (headerList.stream().noneMatch(h -> h.trim().equalsIgnoreCase(required))) {
                throw new IllegalArgumentException(
                        "ERROR: Falta la columna obligatoria '" + required + "' en el CSV"
                );
            }
        }
    }

    /**
     * Valida que el CSV sea del tipo WEEKLY
     */
    private void validateWeeklyCsvFormat(String[] headers) throws IllegalArgumentException {
        List<String> headerList = Arrays.asList(headers);

        // Columna que DEBE existir en Weekly pero NO en Daily
        boolean hasWeek = headerList.stream()
                .anyMatch(h -> h.trim().equalsIgnoreCase("Week"));

        // Columna que NO debe existir en Weekly (solo en Daily)
        boolean hasDay = headerList.stream()
                .anyMatch(h -> h.trim().equalsIgnoreCase("Day"));

        if (!hasWeek && hasDay) {
            throw new IllegalArgumentException(
                    "ERROR: Archivo incorrecto. Has subido un CSV DIARIO al endpoint de SEMANALES. " +
                            "Por favor, usa el endpoint /api/v1/metrics/upload/daily"
            );
        }

        if (!hasWeek) {
            throw new IllegalArgumentException(
                    "ERROR: El CSV no contiene la columna 'Week'. Verifica que sea un archivo de métricas semanales válido."
            );
        }

        // Validar columnas mínimas requeridas
        String[] requiredColumns = {"Rider ID", "Week", "City"};
        for (String required : requiredColumns) {
            if (headerList.stream().noneMatch(h -> h.trim().equalsIgnoreCase(required))) {
                throw new IllegalArgumentException(
                        "ERROR: Falta la columna obligatoria '" + required + "' en el CSV"
                );
            }
        }
    }

    private Map<String, Integer> mapHeaders(String[] headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim(), i);
        }
        return map;
    }

    private RiderMetricsDailyEntity parseDailyRow(String[] row, Map<String, Integer> headers) {
        // Validar campos obligatorios ANTES de crear el objeto
        Integer riderId = parseInteger(row[headers.get("Rider ID")]);
        LocalDate day = parseDate(row[headers.get("Day")]);

        if (riderId == null) {
            throw new IllegalArgumentException("Rider ID es obligatorio");
        }

        if (day == null) {
            throw new IllegalArgumentException("Day es obligatorio o tiene formato inválido: " + row[headers.get("Day")]);
        }

        return RiderMetricsDailyEntity.builder()
                .riderId(riderId)
                .day(day)
                .city(getValue(row, headers, "City"))
                .vehicle(getValue(row, headers, "Vehicle"))
                .phone(getValue(row, headers, "Phone"))
                .workedHours(parseBigDecimal(getValue(row, headers, "Worked Hours")))
                .totalCompletedDeliveries(parseInteger(getValue(row, headers, "Total Completed Deliveries")))
                .totalAssigned(parseInteger(getValue(row, headers, "Total Assigned")))
                .totalReassigned(parseInteger(getValue(row, headers, "Total Reassigned")))
                .totalCancelledDeliveries(parseInteger(getValue(row, headers, "Total Cancelled Deliveries")))
                .totalCancelledNearCustomer(parseInteger(getValue(row, headers, "Total Cancelled Deliveries Near Customer")))
                .utr(parseBigDecimal(getValue(row, headers, "UTR")))
                .efficiency(parseBigDecimal(getValue(row, headers, "Efficiency")))
                .totalStackedDeliveries(parseInteger(getValue(row, headers, "Total Stacked Deliveries")))
                .totalStackedIntravendor(parseInteger(getValue(row, headers, "Total Stacked Deliveries - Intravendor")))
                .totalStackedIntervendor(parseInteger(getValue(row, headers, "Total Stacked Deliveries - Intervendor")))
                .drivenDistanceKm(parseBigDecimal(getValue(row, headers, "Driven Distance - Google (km)")))
                .totalWtpMin(parseBigDecimal(getValue(row, headers, "Total WTP (in min)")))
                .totalWtdMin(parseBigDecimal(getValue(row, headers, "Total WTD (in min)")))
                .bookedShifts(parseInteger(getValue(row, headers, "Booked Shifts")))
                .unbookedShifts(parseInteger(getValue(row, headers, "Unbooked Shifts")))
                .balanceEod(getValue(row, headers, "Balance at EoD"))
                .totalCdt(parseBigDecimal(getValue(row, headers, "Total CDT")))
                .avgCdt(parseBigDecimal(getValue(row, headers, "AVG CDT")))
                .pdSpeedKmh(parseBigDecimal(getValue(row, headers, "PD Speed (km/h)")))
                .tips(parseBigDecimal(getValue(row, headers, "Tips")))
                .build();
    }

    private RiderMetricsWeeklyEntity parseWeeklyRow(String[] row, Map<String, Integer> headers) {
        return RiderMetricsWeeklyEntity.builder()
                .riderId(parseInteger(row[headers.get("Rider ID")]))
                .week(getValue(row, headers, "Week"))
                .city(getValue(row, headers, "City"))
                .vehicle(getValue(row, headers, "Vehicle"))
                .phone(getValue(row, headers, "Phone"))
                .workedHours(parseBigDecimal(getValue(row, headers, "Worked Hours")))
                .totalCompletedDeliveries(parseInteger(getValue(row, headers, "Total Completed Deliveries")))
                .totalAssigned(parseInteger(getValue(row, headers, "Total Assigned")))
                .totalReassigned(parseInteger(getValue(row, headers, "Total Reassigned")))
                .totalCancelledDeliveries(parseInteger(getValue(row, headers, "Total Cancelled Deliveries")))
                .totalCancelledNearCustomer(parseInteger(getValue(row, headers, "Total Cancelled Deliveries Near Customer")))
                .utr(parseBigDecimal(getValue(row, headers, "UTR")))
                .efficiency(parseBigDecimal(getValue(row, headers, "Efficiency")))
                .totalStackedDeliveries(parseInteger(getValue(row, headers, "Total Stacked Deliveries")))
                .totalStackedIntravendor(parseInteger(getValue(row, headers, "Total Stacked Deliveries - Intravendor")))
                .totalStackedIntervendor(parseInteger(getValue(row, headers, "Total Stacked Deliveries - Intervendor")))
                .drivenDistanceKm(parseBigDecimal(getValue(row, headers, "Driven Distance - Google (km)")))
                .totalWtpMin(parseBigDecimal(getValue(row, headers, "Total WTP (in min)")))
                .totalWtdMin(parseBigDecimal(getValue(row, headers, "Total WTD (in min)")))
                .bookedShifts(parseInteger(getValue(row, headers, "Booked Shifts")))
                .unbookedShifts(parseInteger(getValue(row, headers, "Unbooked Shifts")))
                .balanceEod(getValue(row, headers, "Balance at EoD"))
                .totalCdt(parseBigDecimal(getValue(row, headers, "Total CDT")))
                .avgCdt(parseBigDecimal(getValue(row, headers, "AVG CDT")))
                .pdSpeedKmh(parseBigDecimal(getValue(row, headers, "PD Speed (km/h)")))
                .tips(parseBigDecimal(getValue(row, headers, "Tips")))
                .build();
    }

    private String getValue(String[] row, Map<String, Integer> headers, String header) {
        Integer index = headers.get(header);
        if (index == null || index >= row.length) return null;
        String value = row[index].trim();
        return value.isEmpty() ? null : value;
    }

    private Integer parseInteger(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return new BigDecimal(value.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.trim().isEmpty()) return null;

        value = value.trim();

        try {
            // Formato DD/MM/YYYY
            DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            return LocalDate.parse(value, formatter1);
        } catch (Exception e1) {
            try {
                // Formato "25 sept 2025" (español con día de 2 dígitos)
                DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("dd MMM yyyy", new Locale("es", "ES"));
                return LocalDate.parse(value, formatter2);
            } catch (Exception e2) {
                try {
                    // Formato "1 oct 2025" (español con día de 1 dígito) - NUEVO
                    DateTimeFormatter formatter3 = DateTimeFormatter.ofPattern("d MMM yyyy", new Locale("es", "ES"));
                    return LocalDate.parse(value, formatter3);
                } catch (Exception e3) {
                    try {
                        // Formato "1 Sep 2025" (inglés con día de 1 dígito)
                        DateTimeFormatter formatter4 = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
                        return LocalDate.parse(value, formatter4);
                    } catch (Exception e4) {
                        try {
                            // Formato ISO: 2025-09-25
                            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
                        } catch (Exception e5) {
                            try {
                                // Formato DD-MM-YYYY
                                DateTimeFormatter formatter5 = DateTimeFormatter.ofPattern("dd-MM-yyyy");
                                return LocalDate.parse(value, formatter5);
                            } catch (Exception e6) {
                                logger.warn("Error parseando fecha '{}': ningún formato coincide", value);
                                return null;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Valida que la fila tenga la estructura mínima esperada para Daily CSV
     */
    private void validateRowStructure(String[] row, Map<String, Integer> headerMap, int rowNumber) {
        // Validar que la fila no esté vacía
        if (row == null || row.length == 0) {
            throw new IllegalArgumentException("Fila vacía");
        }

        // Validar que existan los índices de campos obligatorios
        Integer riderIdIndex = headerMap.get("Rider ID");
        Integer dayIndex = headerMap.get("Day");

        if (riderIdIndex == null) {
            throw new IllegalArgumentException("Columna 'Rider ID' no encontrada en headers");
        }

        if (dayIndex == null) {
            throw new IllegalArgumentException("Columna 'Day' no encontrada en headers");
        }

        // Validar que la fila tenga suficientes columnas
        if (row.length <= riderIdIndex) {
            throw new IllegalArgumentException("Fila incompleta: falta columna 'Rider ID'");
        }

        if (row.length <= dayIndex) {
            throw new IllegalArgumentException("Fila incompleta: falta columna 'Day'");
        }

        // Validar que los campos obligatorios no estén vacíos
        String riderId = row[riderIdIndex] != null ? row[riderIdIndex].trim() : "";
        String day = row[dayIndex] != null ? row[dayIndex].trim() : "";

        if (riderId.isEmpty()) {
            throw new IllegalArgumentException("Campo 'Rider ID' está vacío");
        }

        if (day.isEmpty()) {
            throw new IllegalArgumentException("Campo 'Day' está vacío");
        }
    }

    /**
     * Valida que la fila tenga la estructura mínima esperada para Weekly CSV
     */
    private void validateRowStructureWeekly(String[] row, Map<String, Integer> headerMap, int rowNumber) {
        // Validar que la fila no esté vacía
        if (row == null || row.length == 0) {
            throw new IllegalArgumentException("Fila vacía");
        }

        // Validar que existan los índices de campos obligatorios
        Integer riderIdIndex = headerMap.get("Rider ID");
        Integer weekIndex = headerMap.get("Week");

        if (riderIdIndex == null) {
            throw new IllegalArgumentException("Columna 'Rider ID' no encontrada en headers");
        }

        if (weekIndex == null) {
            throw new IllegalArgumentException("Columna 'Week' no encontrada en headers");
        }

        // Validar que la fila tenga suficientes columnas
        if (row.length <= riderIdIndex) {
            throw new IllegalArgumentException("Fila incompleta: falta columna 'Rider ID'");
        }

        if (row.length <= weekIndex) {
            throw new IllegalArgumentException("Fila incompleta: falta columna 'Week'");
        }

        // Validar que los campos obligatorios no estén vacíos
        String riderId = row[riderIdIndex] != null ? row[riderIdIndex].trim() : "";
        String week = row[weekIndex] != null ? row[weekIndex].trim() : "";

        if (riderId.isEmpty()) {
            throw new IllegalArgumentException("Campo 'Rider ID' está vacío");
        }

        if (week.isEmpty()) {
            throw new IllegalArgumentException("Campo 'Week' está vacío");
        }
    }
}