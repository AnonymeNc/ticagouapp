package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByIdAndStructureId(UUID id, UUID structureId);

    List<Invoice> findByStructureIdAndMonthOrderByNumberAsc(UUID structureId, LocalDate month);

    List<Invoice> findByStructureIdOrderByIssuedOnDescNumberDesc(UUID structureId);

    List<Invoice> findByFamilyIdOrderByMonthDescNumberDesc(UUID familyId);

    List<Invoice> findByFamilyIdIn(Collection<UUID> familyIds);

    boolean existsByFamilyIdIn(Collection<UUID> familyIds);
}
