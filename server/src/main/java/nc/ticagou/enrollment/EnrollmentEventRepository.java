package nc.ticagou.enrollment;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EnrollmentEventRepository extends JpaRepository<EnrollmentEvent, UUID> {

    List<EnrollmentEvent> findByRequestIdOrderByCreatedAtDesc(UUID requestId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from EnrollmentEvent e where e.requestId in :ids")
    int deleteByRequestIdIn(Collection<UUID> ids);
}
