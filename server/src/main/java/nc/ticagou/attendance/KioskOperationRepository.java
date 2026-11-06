package nc.ticagou.attendance;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface KioskOperationRepository extends JpaRepository<KioskOperation, UUID> {

    List<KioskOperation> findByStructureIdAndOutcomeAndReceivedAtAfterOrderByReceivedAtDesc(
            UUID structureId, KioskOperation.Outcome outcome, Instant after);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from KioskOperation o where o.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
