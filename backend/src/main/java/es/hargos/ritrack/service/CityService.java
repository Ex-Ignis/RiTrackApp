package es.hargos.ritrack.service;

import es.hargos.ritrack.client.GlovoClient;
import es.hargos.ritrack.dto.StartingPointDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for city-related operations
 * Provides starting points and other city data
 */
@Service
public class CityService {

    private static final Logger logger = LoggerFactory.getLogger(CityService.class);

    @Autowired
    private GlovoClient glovoClient;

    /**
     * Get all starting points for a city
     *
     * @param tenantId Tenant ID
     * @param cityId City ID
     * @return List of starting points
     * @throws Exception if API call fails
     *
     * Cache: 30 days TTL (starting points rarely change)
     * Key: "tenantId-cityId" to ensure tenant isolation
     */
    @Cacheable(value = "starting-points", key = "#tenantId + '-' + #cityId")
    public List<StartingPointDto> getStartingPoints(Long tenantId, Integer cityId) throws Exception {
        logger.info("Tenant {}: Fetching starting points for city {} from Glovo API", tenantId, cityId);

        try {
            // Call Glovo Rooster API: GET /v3/external/starting-points?city_id={cityId}
            List<Object> rawDataObjects = glovoClient.getStartingPointsByCity(tenantId, cityId);

            // Convert List<Object> to List<Map<String, Object>>
            List<Map<String, Object>> rawData = rawDataObjects.stream()
                .filter(obj -> obj instanceof Map)
                .map(obj -> (Map<String, Object>) obj)
                .collect(Collectors.toList());

            // Map to DTO
            List<StartingPointDto> startingPoints = rawData.stream()
                .map(data -> StartingPointDto.builder()
                    .id((Integer) data.get("id"))
                    .name((String) data.get("name"))
                    .cityId(cityId) // Use cityId from parameter (source of truth)
                    .build())
                .collect(Collectors.toList());

            logger.info("Tenant {}: Retrieved {} starting points for city {}",
                tenantId, startingPoints.size(), cityId);

            return startingPoints;

        } catch (Exception e) {
            logger.error("Tenant {}: Error fetching starting points for city {}: {}",
                tenantId, cityId, e.getMessage());
            throw e;
        }
    }

    /**
     * Get IDs of all starting points for a city (convenience method)
     *
     * @param tenantId Tenant ID
     * @param cityId City ID
     * @return List of starting point IDs
     * @throws Exception if API call fails
     */
    public List<Integer> getStartingPointIds(Long tenantId, Integer cityId) throws Exception {
        List<StartingPointDto> startingPoints = getStartingPoints(tenantId, cityId);
        return startingPoints.stream()
            .map(StartingPointDto::getId)
            .collect(Collectors.toList());
    }
}
