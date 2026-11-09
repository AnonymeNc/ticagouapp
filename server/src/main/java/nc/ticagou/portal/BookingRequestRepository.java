package nc.ticagou.portal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRequestRepository extends JpaRepository<BookingRequest, UUID> {

    Optional<BookingRequest> findByIdAndStructureId(UUID id, UUID structureId);

    List<BookingRequest> findByStructureIdAndStatusOrderByDayAscRequestedAtAsc(UUID structureId, BookingRequest.Status status);

    List<BookingRequest> findByStructureIdAndDayAndStatus(UUID structureId, LocalDate day, BookingRequest.Status status);

    List<BookingRequest> findByStructureIdAndDayGreaterThanEqualOrderByDayAscRequestedAtAsc(UUID structureId, LocalDate from);
}
