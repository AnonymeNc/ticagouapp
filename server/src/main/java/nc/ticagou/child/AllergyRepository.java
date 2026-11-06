package nc.ticagou.child;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AllergyRepository extends JpaRepository<Allergy, UUID> {

    List<Allergy> findByChildIdOrderByDisplayOrderAsc(UUID childId);

    List<Allergy> findByStructureId(UUID structureId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Allergy a where a.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
