package nc.ticagou.staff;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffSettingsRepository extends JpaRepository<StaffSettings, UUID> {

    List<StaffSettings> findByStructureIdOrderByValidFromDesc(UUID structureId);
}
