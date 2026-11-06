package nc.ticagou.child;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChildRepository extends JpaRepository<Child, UUID> {

    List<Child> findByStructureId(UUID structureId);

    List<Child> findByFamilyIdOrderByBirthDateAsc(UUID familyId);

    Optional<Child> findByIdAndStructureId(UUID id, UUID structureId);

    boolean existsBySectionId(UUID sectionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Child c where c.id in :ids")
    int deleteByIdIn(Collection<UUID> ids);
}
