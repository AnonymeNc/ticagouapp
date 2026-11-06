package nc.ticagou.staff;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {

    List<StaffProfile> findByStructureId(UUID structureId);
}
