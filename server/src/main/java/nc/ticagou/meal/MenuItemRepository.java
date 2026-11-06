package nc.ticagou.meal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {

    List<MenuItem> findByWeekIdOrderByDayOfWeekAscCourseAsc(UUID weekId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MenuItem i where i.weekId = :weekId")
    int deleteByWeekId(UUID weekId);
}
