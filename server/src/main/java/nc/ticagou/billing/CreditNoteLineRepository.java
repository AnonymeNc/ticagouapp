package nc.ticagou.billing;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditNoteLineRepository extends JpaRepository<CreditNoteLine, UUID> {

    List<CreditNoteLine> findByCreditNoteIdOrderByPositionAsc(UUID creditNoteId);
}
