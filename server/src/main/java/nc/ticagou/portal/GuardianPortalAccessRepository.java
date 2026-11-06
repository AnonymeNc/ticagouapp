package nc.ticagou.portal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardianPortalAccessRepository extends JpaRepository<GuardianPortalAccess, UUID> {

    boolean existsByFamilyId(UUID familyId);

    List<GuardianPortalAccess> findByFamilyId(UUID familyId);

    List<GuardianPortalAccess> findByFamilyIdIn(Collection<UUID> familyIds);

    List<GuardianPortalAccess> findByStructureId(UUID structureId);
}
