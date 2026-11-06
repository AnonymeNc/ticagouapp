package nc.ticagou.child;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ConsentRepository extends JpaRepository<Consent, UUID> {

    List<Consent> findByChildId(UUID childId);

    /** Enfants dont l'autorisation de ce type est accordée. */
    @Query("select c.childId from Consent c where c.structureId = :structureId and c.type = :type and c.granted = true")
    List<UUID> grantedChildIds(UUID structureId, Consent.Type type);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Consent c where c.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
