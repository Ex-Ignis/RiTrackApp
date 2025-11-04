package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.TenantSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantSettingsRepository extends JpaRepository<TenantSettingsEntity, Long> {

    Optional<TenantSettingsEntity> findByTenantIdAndSettingKey(Long tenantId, String settingKey);

    List<TenantSettingsEntity> findByTenantId(Long tenantId);

    void deleteByTenantIdAndSettingKey(Long tenantId, String settingKey);
}
