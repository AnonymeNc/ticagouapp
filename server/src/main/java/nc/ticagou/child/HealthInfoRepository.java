package nc.ticagou.child;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface HealthInfoRepository extends JpaRepository<HealthInfo, UUID> {

    List<HealthInfo> findByStructureId(UUID structureId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update HealthInfo h set h.paiDocumentId = null where h.paiDocumentId = :documentId")
    int clearPaiDocument(UUID documentId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from HealthInfo h where h.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
