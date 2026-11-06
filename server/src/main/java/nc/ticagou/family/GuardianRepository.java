package nc.ticagou.family;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GuardianRepository extends JpaRepository<Guardian, UUID> {

    List<Guardian> findByFamilyIdOrderByDisplayOrderAscLastNameAsc(UUID familyId);

    List<Guardian> findByFamilyIdIn(Collection<UUID> familyIds);

    Optional<Guardian> findByIdAndStructureId(UUID id, UUID structureId);

    long countByFamilyIdAndLegalGuardianTrue(UUID familyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Guardian g where g.familyId = :familyId")
    int deleteByFamilyId(UUID familyId);
}
