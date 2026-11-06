package nc.ticagou.meal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MealNoteRepository extends JpaRepository<MealNote, UUID> {

    List<MealNote> findByStructureIdAndDay(UUID structureId, LocalDate day);

    Optional<MealNote> findByChildIdAndDay(UUID childId, LocalDate day);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MealNote n where n.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
