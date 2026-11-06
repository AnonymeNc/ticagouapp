package nc.ticagou.portal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PortalOutboxRepository extends JpaRepository<PortalOutboxItem, Long> {

    boolean existsByStructureIdAndTypeAndEntityIdAndSentAtIsNull(UUID structureId, PortalItemType type, UUID entityId);

    List<PortalOutboxItem> findByStructureIdAndSentAtIsNullOrderBySeqAsc(UUID structureId, Limit limit);

    long countByStructureIdAndSentAtIsNull(UUID structureId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PortalOutboxItem o set o.sentAt = :at where o.seq in :seqs")
    int markSent(java.util.Collection<Long> seqs, java.time.Instant at);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PortalOutboxItem o where o.structureId = :structureId")
    int deleteByStructureId(UUID structureId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PortalOutboxItem o where o.sentAt is not null and o.sentAt < :before")
    int deleteSentBefore(java.time.Instant before);
}
