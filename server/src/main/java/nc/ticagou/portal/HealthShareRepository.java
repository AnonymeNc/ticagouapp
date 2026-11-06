package nc.ticagou.portal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface HealthShareRepository extends JpaRepository<HealthShare, UUID> {

    List<HealthShare> findByChildIdOrderBySharedAtAsc(UUID childId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from HealthShare h where h.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
