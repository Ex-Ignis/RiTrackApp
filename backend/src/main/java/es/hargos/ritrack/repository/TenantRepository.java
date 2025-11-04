package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, Long> {

    Optional<TenantEntity> findByName(String name);

    Optional<TenantEntity> findBySchemaName(String schemaName);

    Optional<TenantEntity> findByHargosTenantId(Long hargosTenantId);

    List<TenantEntity> findByIsActive(Boolean isActive);

    boolean existsBySchemaName(String schemaName);
}
