package nc.ticagou.child;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AuthorizedPersonRepository extends JpaRepository<AuthorizedPerson, UUID> {

    List<AuthorizedPerson> findByChildIdOrderByLastNameAscFirstNameAsc(UUID childId);

    List<AuthorizedPerson> findByChildIdIn(Collection<UUID> childIds);

    Optional<AuthorizedPerson> findByIdAndStructureId(UUID id, UUID structureId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AuthorizedPerson p where p.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
