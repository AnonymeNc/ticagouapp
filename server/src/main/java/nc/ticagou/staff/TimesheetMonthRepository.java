package nc.ticagou.staff;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TimesheetMonthRepository extends JpaRepository<TimesheetMonth, UUID> {

    Optional<TimesheetMonth> findByStructureIdAndMonth(UUID structureId, LocalDate month);

    List<TimesheetMonth> findByStructureIdAndMonthIn(UUID structureId, Collection<LocalDate> months);
}
