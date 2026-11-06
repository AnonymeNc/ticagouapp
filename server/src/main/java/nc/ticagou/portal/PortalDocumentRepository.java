package nc.ticagou.portal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PortalDocumentRepository extends JpaRepository<PortalDocument, UUID> {

    Optional<PortalDocument> findByIdAndStructureId(UUID id, UUID structureId);

    List<PortalDocument> findByStructureIdAndStatusOrderByProposedAtDesc(UUID structureId, PortalDocument.Status status);

    List<PortalDocument> findByFamilyIdOrderByProposedAtDesc(UUID familyId);

    List<PortalDocument> findByChildIdIn(Collection<UUID> childIds);

    boolean existsByContractIdAndVersionNumberAndStatusIn(UUID contractId, int versionNumber,
                                                          Collection<PortalDocument.Status> statuses);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PortalDocument d where d.familyId = :familyId")
    int deleteByFamilyId(UUID familyId);
}
