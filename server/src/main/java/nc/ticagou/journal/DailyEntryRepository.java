package nc.ticagou.journal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DailyEntryRepository extends JpaRepository<DailyEntry, UUID> {

    Optional<DailyEntry> findByIdAndStructureId(UUID id, UUID structureId);

    List<DailyEntry> findByStructureIdAndDayOrderByOccurredAtAsc(UUID structureId, LocalDate day);

    @Query("SELECT DISTINCT e FROM DailyEntry e JOIN e.childIds c WHERE e.structureId = :structureId AND e.day = :day "
            + "AND c = :childId ORDER BY e.occurredAt")
    List<DailyEntry> findForChild(UUID structureId, LocalDate day, UUID childId);

    @Query("SELECT DISTINCT e FROM DailyEntry e JOIN e.childIds c WHERE c IN :childIds")
    List<DailyEntry> findByChildIdIn(Collection<UUID> childIds);

    @Query("SELECT DISTINCT e.id FROM DailyEntry e JOIN e.childIds c WHERE c = :childId AND e.day >= :since")
    List<UUID> idsForChildSince(UUID childId, LocalDate since);

    @Query("SELECT DISTINCT e.id FROM DailyEntry e JOIN e.childIds c WHERE c = :childId AND e.kind = :kind AND e.day >= :since")
    List<UUID> idsForChildSince(UUID childId, DailyEntry.Kind kind, LocalDate since);
}
