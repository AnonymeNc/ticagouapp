package nc.ticagou.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByLoginIgnoreCase(String login);

    Optional<AppUser> findByIdAndStructureId(UUID id, UUID structureId);

    List<AppUser> findByStructureIdOrderByLastNameAscFirstNameAsc(UUID structureId);

    List<AppUser> findByStructureIdAndActiveTrueAndPinHashIsNotNullOrderByFirstNameAscLastNameAsc(UUID structureId);

    boolean existsByLoginIgnoreCase(String login);

    boolean existsByLoginIgnoreCaseAndIdNot(String login, UUID id);

    long countByStructureIdAndRoleAndActiveTrue(UUID structureId, Role role);
}
