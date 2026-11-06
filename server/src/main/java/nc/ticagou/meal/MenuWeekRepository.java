package nc.ticagou.meal;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuWeekRepository extends JpaRepository<MenuWeek, UUID> {

    Optional<MenuWeek> findByStructureIdAndWeekStart(UUID structureId, LocalDate weekStart);
}
