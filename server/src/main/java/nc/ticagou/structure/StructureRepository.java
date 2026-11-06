package nc.ticagou.structure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StructureRepository extends JpaRepository<Structure, UUID> {
}
