package nc.ticagou.portal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FamilyMessageRepository extends JpaRepository<FamilyMessage, UUID> {

    boolean existsByPortalRef(UUID portalRef);

    List<FamilyMessage> findByStructureIdAndFamilyIdOrderBySentAtAsc(UUID structureId, UUID familyId);

    List<FamilyMessage> findByStructureIdOrderBySentAtDesc(UUID structureId);

    long countByStructureIdAndReadAtIsNull(UUID structureId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FamilyMessage m where m.familyId = :familyId")
    int deleteByFamilyId(UUID familyId);
}
