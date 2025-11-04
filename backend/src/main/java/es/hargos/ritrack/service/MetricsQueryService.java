package es.hargos.ritrack.service;

import es.hargos.ritrack.dto.RiderMetricsDailyDto;
import es.hargos.ritrack.dto.RiderMetricsWeeklyDto;
import es.hargos.ritrack.entity.RiderMetricsWeeklyEntity;
import es.hargos.ritrack.repository.RiderMetricsDailyRepository;
import es.hargos.ritrack.repository.RiderMetricsWeeklyRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MetricsQueryService {

    private final RiderMetricsDailyRepository dailyRepository;
    private final RiderMetricsWeeklyRepository weeklyRepository;

    public MetricsQueryService(RiderMetricsDailyRepository dailyRepository,
                               RiderMetricsWeeklyRepository weeklyRepository) {
        this.dailyRepository = dailyRepository;
        this.weeklyRepository = weeklyRepository;
    }

    // MÉTRICAS DIARIAS

    public List<RiderMetricsDailyDto> getDailyMetricsByRider(Integer riderId) {
        return dailyRepository.findByRiderId(riderId).stream()
                .map(RiderMetricsDailyDto::fromEntity)
                .collect(Collectors.toList());
    }

    public List<RiderMetricsDailyDto> getDailyMetricsByDateRange(LocalDate startDate, LocalDate endDate) {
        return dailyRepository.findByDayBetween(startDate, endDate).stream()
                .map(RiderMetricsDailyDto::fromEntity)
                .collect(Collectors.toList());
    }

    public List<RiderMetricsDailyDto> getDailyMetricsByRiderAndDateRange(
            Integer riderId, LocalDate startDate, LocalDate endDate) {
        return dailyRepository.findByRiderIdAndDayBetween(riderId, startDate, endDate).stream()
                .map(RiderMetricsDailyDto::fromEntity)
                .collect(Collectors.toList());
    }

    public RiderMetricsDailyDto getDailyMetricByRiderAndDay(Integer riderId, LocalDate day) {
        return dailyRepository.findByRiderIdAndDay(riderId, day)
                .map(RiderMetricsDailyDto::fromEntity)
                .orElse(null);
    }

    // MÉTRICAS SEMANALES

    public List<RiderMetricsWeeklyDto> getWeeklyMetricsByRider(Integer riderId) {
        return weeklyRepository.findByRiderId(riderId).stream()
                .map(RiderMetricsWeeklyDto::fromEntity)
                .collect(Collectors.toList());
    }

    public List<RiderMetricsWeeklyDto> getWeeklyMetricsByWeek(String week) {
        return weeklyRepository.findByWeek(week).stream()
                .map(RiderMetricsWeeklyDto::fromEntity)
                .collect(Collectors.toList());
    }

    public RiderMetricsWeeklyDto getWeeklyMetricByRiderAndWeek(Integer riderId, String week) {
        return weeklyRepository.findByRiderIdAndWeek(riderId, week)
                .map(RiderMetricsWeeklyDto::fromEntity)
                .orElse(null);
    }

    public List<RiderMetricsWeeklyDto> getWeeklyMetricsByRiderAndWeekRange(Integer riderId, String startWeek, String endWeek) {
        List<RiderMetricsWeeklyEntity> entities = weeklyRepository.findByRiderIdAndWeekBetween(riderId, startWeek, endWeek);
        return entities.stream()
                .map(RiderMetricsWeeklyDto::fromEntity)
                .collect(Collectors.toList());
    }
}