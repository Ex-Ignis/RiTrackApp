package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.GlovoCredentialsEntity;
import es.hargos.ritrack.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GlovoCredentialsRepository extends JpaRepository<GlovoCredentialsEntity, Long> {

    Optional<GlovoCredentialsEntity> findByTenant(TenantEntity tenant);

    Optional<GlovoCredentialsEntity> findByTenantIdAndIsActive(Long tenantId, Boolean isActive);

    Optional<GlovoCredentialsEntity> findByTenantIdAndIsActiveAndIsValidated(Long tenantId, Boolean isActive, Boolean isValidated);

    Optional<GlovoCredentialsEntity> findByTenantId(Long tenantId);
}
