package nc.ticagou.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.PageDto;

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('DIRECTION')")
@Tag(name = "Journal d'audit", description = "Consultation réservée à la direction")
public class AuditController {

    private final AuditEventRepository events;
    private final ChildRecordAccessRepository childAccesses;
    private final Clock clock;

    public AuditController(AuditEventRepository events, ChildRecordAccessRepository childAccesses, Clock clock) {
        this.events = events;
        this.childAccesses = childAccesses;
        this.clock = clock;
    }

    @GetMapping("/events")
    @Transactional(readOnly = true)
    @Operation(summary = "Événements du journal (connexions, modifications, refus d'accès)")
    public PageDto<AuditEventDto> events(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        var structureId = CurrentUser.get().structureId();
        Specification<AuditEvent> spec = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("structureId"), structureId));
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("occurredAt"), startOf(from)));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.<Instant>get("occurredAt"), startOf(to.plusDays(1))));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt", "id"));
        return PageDto.of(events.findAll(spec, pageable), AuditEventDto::from);
    }

    @GetMapping("/child-access")
    @Transactional(readOnly = true)
    @Operation(summary = "Consultations de dossiers enfants, éventuellement pour un enfant")
    public PageDto<ChildAccessDto> childAccess(
            @RequestParam(required = false) UUID childId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        var structureId = CurrentUser.get().structureId();
        var pageable = PageRequest.of(page, size);
        var result = childId == null
                ? childAccesses.findByStructureIdOrderByAccessedAtDesc(structureId, pageable)
                : childAccesses.findByStructureIdAndChildIdOrderByAccessedAtDesc(structureId, childId, pageable);
        return PageDto.of(result, ChildAccessDto::from);
    }

    private Instant startOf(LocalDate date) {
        return date.atStartOfDay(clock.getZone()).toInstant();
    }

    public record AuditEventDto(long id, Instant occurredAt, UUID actorUserId, String actorName, AuditAction action,
                                String targetType, String targetId, String details, String clientIp) {

        static AuditEventDto from(AuditEvent e) {
            return new AuditEventDto(e.getId(), e.getOccurredAt(), e.getActorUserId(), e.getActorName(),
                    e.getAction(), e.getTargetType(), e.getTargetId(), e.getDetails(), e.getClientIp());
        }
    }

    public record ChildAccessDto(long id, UUID childId, UUID userId, String userName, Instant accessedAt,
                                 ChildAccessContext context, String clientIp) {

        static ChildAccessDto from(ChildRecordAccess a) {
            return new ChildAccessDto(a.getId(), a.getChildId(), a.getUserId(), a.getUserName(),
                    a.getAccessedAt(), a.getContext(), a.getClientIp());
        }
    }
}
