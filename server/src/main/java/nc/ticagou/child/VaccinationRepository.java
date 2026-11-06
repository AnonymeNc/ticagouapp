package nc.ticagou.child;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface VaccinationRepository extends JpaRepository<Vaccination, UUID> {

    List<Vaccination> findByChildIdOrderByDisplayOrderAsc(UUID childId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Vaccination v where v.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
