package nc.ticagou.meal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import nc.ticagou.meal.MealCounter.Meal;

public interface MealAdjustmentRepository extends JpaRepository<MealAdjustment, UUID> {

    List<MealAdjustment> findByStructureIdAndDayBetween(UUID structureId, LocalDate from, LocalDate to);

    Optional<MealAdjustment> findByChildIdAndDayAndMeal(UUID childId, LocalDate day, Meal meal);

    List<MealAdjustment> findByChildIdInOrderByDayAsc(Collection<UUID> childIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MealAdjustment a where a.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
