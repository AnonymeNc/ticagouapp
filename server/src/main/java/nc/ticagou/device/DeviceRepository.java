package nc.ticagou.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByStructureIdOrderByCreatedAtAsc(UUID structureId);

    Optional<Device> findByIdAndStructureId(UUID id, UUID structureId);

    List<Device> findByPairingCodeHash(String pairingCodeHash);
}
