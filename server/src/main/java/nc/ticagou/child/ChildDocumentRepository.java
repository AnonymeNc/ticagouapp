package nc.ticagou.child;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChildDocumentRepository extends JpaRepository<ChildDocument, UUID> {

    List<ChildDocument> findByChildIdOrderByCreatedAtDesc(UUID childId);

    List<ChildDocument> findByChildIdIn(Collection<UUID> childIds);

    Optional<ChildDocument> findByIdAndStructureId(UUID id, UUID structureId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChildDocument d where d.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
