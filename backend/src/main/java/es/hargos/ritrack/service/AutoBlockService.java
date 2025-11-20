package es.hargos.ritrack.service;

import es.hargos.ritrack.dto.AutoBlockConfigDto;
import es.hargos.ritrack.dto.AutoBlockCityConfigDto;
import es.hargos.ritrack.dto.RiderLocationDto;
import es.hargos.ritrack.entity.AutoBlockCityConfigEntity;
import es.hargos.ritrack.entity.RiderBlockStatusEntity;
import es.hargos.ritrack.repository.AutoBlockCityConfigRepository;
import es.hargos.ritrack.repository.RiderBlockStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Servicio para gestión de auto-bloqueo de riders por saldo de cash alto.
 *
 * LÓGICA DE BLOQUEO:
 * - Bloquea cuando: balance >= cashLimit (ejemplo: >= 150€)
 * - Desbloquea cuando: balance <= (cashLimit - cashLimit*0.20) (ejemplo: <= 120€ si limit=150€)
 * - Hysteresis del 20% para evitar bloqueos/desbloqueos repetitivos
 * - PRIORIDAD: Bloqueos manuales NO son afectados por auto-desbloqueo
 *
 * MULTI-TENANT: Opera sobre el schema del tenant actual configurado por TenantContext.
 */
@Service
public class AutoBlockService {

    private static final Logger logger = LoggerFactory.getLogger(AutoBlockService.class);

    private final RiderBlockStatusRepository blockStatusRepository;
    private final AutoBlockCityConfigRepository cityConfigRepository;
    private final TenantSettingsService tenantSettingsService;
    private final es.hargos.ritrack.client.GlovoClient glovoClient;
    private final RiderDetailService riderDetailService;
    private final CityService cityService;
    private final RoosterCacheService roosterCache;

    @Autowired
    public AutoBlockService(RiderBlockStatusRepository blockStatusRepository,
                             AutoBlockCityConfigRepository cityConfigRepository,
                             TenantSettingsService tenantSettingsService,
                             es.hargos.ritrack.client.GlovoClient glovoClient,
                             RiderDetailService riderDetailService,
                             CityService cityService,
                             RoosterCacheService roosterCache) {
        this.blockStatusRepository = blockStatusRepository;
        this.cityConfigRepository = cityConfigRepository;
        this.tenantSettingsService = tenantSettingsService;
        this.glovoClient = glovoClient;
        this.riderDetailService = riderDetailService;
        this.cityService = cityService;
        this.roosterCache = roosterCache;
    }

    /**
     * Procesa auto-bloqueo para todos los riders de una ciudad.
     * Llamado desde RiderLocationService después de broadcast WebSocket.
     *
     * @param tenantId Tenant ID
     * @param cityId Ciudad ID
     * @param riders Lista de riders con wallet_info cargado
     */
    public void processAutoBlockForCity(Long tenantId, Integer cityId, List<RiderLocationDto> riders) {
        try {
            // 1. Verificar si auto-block está habilitado para esta ciudad específica
            AutoBlockCityConfigDto config = getAutoBlockConfigForCity(tenantId, cityId);

            if (config == null || !config.getEnabled()) {
                logger.debug("Tenant {}, Ciudad {}: Auto-bloqueo deshabilitado o sin configuración", tenantId, cityId);
                return;
            }

            logger.debug("Tenant {}, Ciudad {}: Procesando auto-bloqueo para {} riders (límite: {}€)",
                    tenantId, cityId, riders.size(), config.getCashLimit());

            int blocked = 0;
            int unblocked = 0;
            int skipped = 0;

            // 2. Procesar cada rider
            for (RiderLocationDto rider : riders) {
                // Skip si no tiene balance cargado
                if (rider.getWalletBalance() == null) {
                    continue;
                }

                BlockAction action = determineAction(rider, config);

                switch (action) {
                    case BLOCK:
                        executeBlock(tenantId, rider, config);
                        blocked++;
                        break;

                    case UNBLOCK:
                        executeUnblock(tenantId, rider, config);
                        unblocked++;
                        break;

                    case SKIP_MANUAL:
                        logger.debug("Tenant {}: Rider {} tiene bloqueo manual, no auto-desbloquear",
                                tenantId, rider.getEmployeeId());
                        skipped++;
                        break;

                    case NONE:
                        // No hacer nada
                        break;
                }
            }

            if (blocked > 0 || unblocked > 0 || skipped > 0) {
                logger.info("Tenant {}, Ciudad {}: Auto-bloqueo completado - {} bloqueados, {} desbloqueados, {} skipped (manual)",
                        tenantId, cityId, blocked, unblocked, skipped);
            }

        } catch (Exception e) {
            logger.error("Tenant {}, Ciudad {}: Error procesando auto-bloqueo: {}",
                    tenantId, cityId, e.getMessage(), e);
        }
    }

    /**
     * Obtiene la configuración de auto-bloqueo del tenant (LEGACY - config global).
     * DEPRECATED: Usar getAutoBlockConfigForCity() en su lugar.
     */
    @Deprecated
    public AutoBlockConfigDto getAutoBlockConfig(Long tenantId) {
        Boolean enabled = tenantSettingsService.getBooleanSetting(tenantId, "auto_block_enabled", false);
        BigDecimal cashLimit = tenantSettingsService.getBigDecimalSetting(tenantId, "auto_block_cash_limit",
                new BigDecimal("150.00"));

        // Calcular threshold de desbloqueo (20% menos que el límite)
        BigDecimal unblockThreshold = cashLimit.multiply(new BigDecimal("0.80"))
                .setScale(2, RoundingMode.HALF_UP);

        return AutoBlockConfigDto.builder()
                .enabled(enabled)
                .cashLimit(cashLimit)
                .unblockThreshold(unblockThreshold)
                .hysteresisPercent(20)
                .build();
    }

    /**
     * Actualiza la configuración de auto-bloqueo del tenant (LEGACY - config global).
     * DEPRECATED: Usar updateAutoBlockConfigForCity() en su lugar.
     */
    @Deprecated
    @Transactional
    public AutoBlockConfigDto updateAutoBlockConfig(Long tenantId, AutoBlockConfigDto config) {
        tenantSettingsService.saveSetting(tenantId, "auto_block_enabled", config.getEnabled().toString());
        tenantSettingsService.saveSetting(tenantId, "auto_block_cash_limit", config.getCashLimit().toString());

        logger.info("Tenant {}: Configuración de auto-bloqueo actualizada - enabled={}, limit={}€",
                tenantId, config.getEnabled(), config.getCashLimit());

        return getAutoBlockConfig(tenantId);
    }

    /**
     * Obtiene la configuración de auto-bloqueo para una ciudad específica.
     *
     * @param tenantId Tenant ID
     * @param cityId Ciudad ID
     * @return DTO con configuración de la ciudad, o null si no existe
     */
    public AutoBlockCityConfigDto getAutoBlockConfigForCity(Long tenantId, Integer cityId) {
        Optional<AutoBlockCityConfigEntity> configOpt = cityConfigRepository.findByCityId(cityId);
        return configOpt.map(AutoBlockCityConfigDto::fromEntity).orElse(null);
    }

    /**
     * Obtiene todas las configuraciones de auto-bloqueo por ciudad.
     *
     * @param tenantId Tenant ID
     * @return Lista de configuraciones
     */
    public List<AutoBlockCityConfigDto> getAllCityConfigs(Long tenantId) {
        return cityConfigRepository.findAllOrderedByCityId().stream()
                .map(AutoBlockCityConfigDto::fromEntity)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Crea o actualiza la configuración de auto-bloqueo para una ciudad.
     *
     * @param tenantId Tenant ID
     * @param cityId Ciudad ID
     * @param dto DTO con configuración
     * @return DTO actualizado
     */
    @Transactional
    public AutoBlockCityConfigDto saveAutoBlockConfigForCity(Long tenantId, Integer cityId, AutoBlockCityConfigDto dto) {
        AutoBlockCityConfigEntity entity = cityConfigRepository.findByCityId(cityId)
                .orElse(AutoBlockCityConfigEntity.builder()
                        .cityId(cityId)
                        .createdAt(LocalDateTime.now())
                        .build());

        entity.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : false);
        entity.setCashLimit(dto.getCashLimit() != null ? dto.getCashLimit() : new BigDecimal("150.00"));
        entity.setUpdatedAt(LocalDateTime.now());

        AutoBlockCityConfigEntity saved = cityConfigRepository.save(entity);

        logger.info("Tenant {}, Ciudad {}: Configuración de auto-bloqueo guardada - enabled={}, limit={}€",
                tenantId, cityId, saved.getEnabled(), saved.getCashLimit());

        return AutoBlockCityConfigDto.fromEntity(saved);
    }

    /**
     * Elimina la configuración de auto-bloqueo de una ciudad.
     *
     * @param tenantId Tenant ID
     * @param cityId Ciudad ID
     */
    @Transactional
    public void deleteAutoBlockConfigForCity(Long tenantId, Integer cityId) {
        cityConfigRepository.findByCityId(cityId).ifPresent(config -> {
            cityConfigRepository.delete(config);
            logger.info("Tenant {}, Ciudad {}: Configuración de auto-bloqueo eliminada", tenantId, cityId);
        });
    }

    /**
     * Determina qué acción tomar para un rider.
     */
    private BlockAction determineAction(RiderLocationDto rider, AutoBlockCityConfigDto config) {
        BigDecimal balance = BigDecimal.valueOf(rider.getWalletBalance());
        BigDecimal limit = config.getCashLimit();
        BigDecimal unblockThreshold = config.getUnblockThreshold();

        // Consultar estado actual del rider
        Optional<RiderBlockStatusEntity> statusOpt = blockStatusRepository.findByEmployeeId(rider.getEmployeeId());

        boolean currentlyAutoBlocked = statusOpt.map(RiderBlockStatusEntity::getIsAutoBlocked).orElse(false);
        boolean hasManualBlock = statusOpt.map(RiderBlockStatusEntity::getIsManualBlocked).orElse(false);

        // Si balance >= limit → BLOQUEAR
        if (balance.compareTo(limit) >= 0) {
            if (currentlyAutoBlocked) {
                // Ya está bloqueado, actualizar balance solamente
                updateBalanceOnly(rider.getEmployeeId(), balance);
                return BlockAction.NONE;
            } else {
                // No está auto-bloqueado → bloquear
                return BlockAction.BLOCK;
            }
        }

        // Si balance <= unblockThreshold → DESBLOQUEAR (si está auto-bloqueado)
        if (balance.compareTo(unblockThreshold) <= 0) {
            if (currentlyAutoBlocked) {
                // Está auto-bloqueado → verificar si tiene bloqueo manual
                if (hasManualBlock) {
                    // Tiene bloqueo manual → NO desbloquear (PRIORIDAD)
                    updateBalanceOnly(rider.getEmployeeId(), balance);
                    return BlockAction.SKIP_MANUAL;
                } else {
                    // Solo tiene auto-bloqueo → desbloquear
                    return BlockAction.UNBLOCK;
                }
            } else {
                // No está auto-bloqueado → no hacer nada
                updateBalanceOnly(rider.getEmployeeId(), balance);
                return BlockAction.NONE;
            }
        }

        // Zona de hysteresis (unblockThreshold < balance < limit) → no hacer nada
        updateBalanceOnly(rider.getEmployeeId(), balance);
        return BlockAction.NONE;
    }

    /**
     * Ejecuta el bloqueo de un rider.
     */
    @Transactional
    private void executeBlock(Long tenantId, RiderLocationDto rider, AutoBlockCityConfigDto config) {
        try {
            String employeeId = rider.getEmployeeId();
            Integer riderId = Integer.parseInt(employeeId);
            BigDecimal balance = BigDecimal.valueOf(rider.getWalletBalance());

            logger.info("Tenant {}: AUTO-BLOQUEO rider {} - balance={}€ (límite={}€)",
                    tenantId, employeeId, balance, config.getCashLimit());

            // Bloquear directamente con GlovoClient (starting_points = [])
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("starting_point_ids", java.util.List.of());
            glovoClient.assignStartingPointsToEmployee(tenantId, riderId, payload);

            // Limpiar caché para reflejar cambios
            roosterCache.clearCache(tenantId);

            // Actualizar estado en BD
            RiderBlockStatusEntity status = blockStatusRepository.findByEmployeeId(employeeId)
                    .orElse(new RiderBlockStatusEntity());

            status.setEmployeeId(employeeId);
            status.setIsAutoBlocked(true);
            status.setLastBalance(balance);
            status.setLastBalanceCheck(LocalDateTime.now());
            status.setAutoBlockedAt(LocalDateTime.now());

            if (status.getCreatedAt() == null) {
                status.setCreatedAt(LocalDateTime.now());
            }
            status.setUpdatedAt(LocalDateTime.now());

            blockStatusRepository.save(status);

            logger.info("Tenant {}: Rider {} bloqueado exitosamente", tenantId, employeeId);

        } catch (Exception e) {
            logger.error("Tenant {}: Error bloqueando rider {}: {}",
                    tenantId, rider.getEmployeeId(), e.getMessage(), e);
        }
    }

    /**
     * Ejecuta el desbloqueo de un rider.
     */
    @Transactional
    private void executeUnblock(Long tenantId, RiderLocationDto rider, AutoBlockCityConfigDto config) {
        try {
            String employeeId = rider.getEmployeeId();
            Integer riderId = Integer.parseInt(employeeId);
            BigDecimal balance = BigDecimal.valueOf(rider.getWalletBalance());

            logger.info("Tenant {}: AUTO-DESBLOQUEO rider {} - balance={}€ (umbral={}€)",
                    tenantId, employeeId, balance, config.getUnblockThreshold());

            // Desbloquear directamente con GlovoClient (asignar todos los starting points)
            // 1. Obtener cityId del rider
            Integer cityId = riderDetailService.getRiderCityId(tenantId, riderId);
            if (cityId == null) {
                logger.error("Tenant {}: No se pudo obtener cityId del rider {} para desbloqueo",
                        tenantId, riderId);
                return;
            }

            // 2. Obtener todos los starting points de la ciudad
            java.util.List<Integer> startingPointIds = cityService.getStartingPointIds(tenantId, cityId);

            // 3. Asignar starting points
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("starting_point_ids", startingPointIds);
            glovoClient.assignStartingPointsToEmployee(tenantId, riderId, payload);

            // Limpiar caché para reflejar cambios
            roosterCache.clearCache(tenantId);

            // Actualizar estado en BD
            RiderBlockStatusEntity status = blockStatusRepository.findByEmployeeId(employeeId)
                    .orElse(new RiderBlockStatusEntity());

            status.setEmployeeId(employeeId);
            status.setIsAutoBlocked(false);
            status.setLastBalance(balance);
            status.setLastBalanceCheck(LocalDateTime.now());
            status.setAutoUnblockedAt(LocalDateTime.now());

            if (status.getCreatedAt() == null) {
                status.setCreatedAt(LocalDateTime.now());
            }
            status.setUpdatedAt(LocalDateTime.now());

            blockStatusRepository.save(status);

            logger.info("Tenant {}: Rider {} desbloqueado exitosamente con {} starting points",
                    tenantId, employeeId, startingPointIds.size());

        } catch (Exception e) {
            logger.error("Tenant {}: Error desbloqueando rider {}: {}",
                    tenantId, rider.getEmployeeId(), e.getMessage(), e);
        }
    }

    /**
     * Actualiza solo el balance sin cambiar estado de bloqueo.
     */
    @Transactional
    private void updateBalanceOnly(String employeeId, BigDecimal balance) {
        Optional<RiderBlockStatusEntity> statusOpt = blockStatusRepository.findByEmployeeId(employeeId);

        if (statusOpt.isPresent()) {
            RiderBlockStatusEntity status = statusOpt.get();
            status.setLastBalance(balance);
            status.setLastBalanceCheck(LocalDateTime.now());
            status.setUpdatedAt(LocalDateTime.now());
            blockStatusRepository.save(status);
        } else {
            // Crear registro con estado inicial
            RiderBlockStatusEntity status = RiderBlockStatusEntity.builder()
                    .employeeId(employeeId)
                    .isAutoBlocked(false)
                    .isManualBlocked(false)
                    .lastBalance(balance)
                    .lastBalanceCheck(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            blockStatusRepository.save(status);
        }
    }

    /**
     * Marca un rider como bloqueado/desbloqueado manualmente.
     * Llamado desde RiderAssignmentService cuando el admin bloquea/desbloquea.
     *
     * @param tenantId Tenant ID
     * @param employeeId Employee ID del rider
     * @param isBlocked true para marcar como bloqueado manual, false para quitar marca
     */
    @Transactional
    public void markAsManualBlock(Long tenantId, String employeeId, boolean isBlocked) {
        RiderBlockStatusEntity status = blockStatusRepository.findByEmployeeId(employeeId)
                .orElse(RiderBlockStatusEntity.builder()
                        .employeeId(employeeId)
                        .isAutoBlocked(false)
                        .isManualBlocked(false)
                        .createdAt(LocalDateTime.now())
                        .build());

        status.setIsManualBlocked(isBlocked);
        status.setUpdatedAt(LocalDateTime.now());

        if (isBlocked) {
            status.setManualBlockedAt(LocalDateTime.now());
            logger.info("Tenant {}: Rider {} marcado con BLOQUEO MANUAL", tenantId, employeeId);
        } else {
            status.setManualBlockedAt(null);
            status.setManualBlockReason(null);
            status.setManualBlockedByUserId(null);
            logger.info("Tenant {}: Rider {} BLOQUEO MANUAL removido", tenantId, employeeId);
        }

        blockStatusRepository.save(status);
    }

    /**
     * Obtiene el estado de bloqueo de un rider específico.
     */
    public Optional<RiderBlockStatusEntity> getRiderBlockStatus(String employeeId) {
        return blockStatusRepository.findByEmployeeId(employeeId);
    }

    /**
     * Obtiene lista de riders actualmente auto-bloqueados.
     */
    public List<RiderBlockStatusEntity> getCurrentlyAutoBlocked() {
        return blockStatusRepository.findCurrentlyAutoBlocked();
    }

    /**
     * Enum para acciones de bloqueo.
     */
    private enum BlockAction {
        NONE,           // No hacer nada
        BLOCK,          // Bloquear
        UNBLOCK,        // Desbloquear
        SKIP_MANUAL     // No desbloquear porque tiene bloqueo manual
    }
}
