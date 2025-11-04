package es.hargos.ritrack.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger logger = LoggerFactory.getLogger(CacheConfig.class);

    @Value("${cache.rooster.employees.ttl-minutes:30}")
    private long roosterTtlMinutes;

    @Value("${cache.rooster.employees.max-size:1}")
    private int roosterMaxSize;

    @Value("${cache.live.city.ttl-seconds:30}")
    private long liveTtlSeconds;

    @Value("${cache.live.city.max-entries:100}")
    private int liveMaxEntries;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // Caché de empleados Rooster
        cacheManager.registerCustomCache("rooster-employees",
                Caffeine.newBuilder()
                        .expireAfterWrite(roosterTtlMinutes, TimeUnit.MINUTES)
                        .maximumSize(roosterMaxSize)
                        .recordStats()
                        .build());

        logger.info("Caché configurado - Rooster: {} min TTL, Live temporal: {} seg TTL",
                roosterTtlMinutes, liveTtlSeconds);

        return cacheManager;
    }

    // Getter para que el servicio pueda acceder al TTL de Live
    public long getLiveTtlMillis() {
        return liveTtlSeconds * 1000;
    }
}