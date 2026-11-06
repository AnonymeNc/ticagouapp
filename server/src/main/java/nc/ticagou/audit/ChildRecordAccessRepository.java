package nc.ticagou.audit;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChildRecordAccessRepository extends JpaRepository<ChildRecordAccess, Long> {

    Page<ChildRecordAccess> findByStructureIdOrderByAccessedAtDesc(UUID structureId, Pageable pageable);

    Page<ChildRecordAccess> findByStructureIdAndChildIdOrderByAccessedAtDesc(UUID structureId, UUID childId,
                                                                            Pageable pageable);
}
