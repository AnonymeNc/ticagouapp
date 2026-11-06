package nc.ticagou.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.AbsenceDtos.AbsenceDto;
import nc.ticagou.billing.AbsenceDtos.AbsenceRequest;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildDocument;
import nc.ticagou.child.ChildDocumentRepository;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;

/**
 * Absences signalées au bureau (Présences, fiche enfant). Le motif décide de la déduction ; le justificatif est
 * un document du dossier de l'enfant. Une absence d'un mois déjà facturé ne change plus : la facture émise est
 * immuable, on la corrige par un avoir.
 */
@Service
public class AbsenceService {

    private final ChildAbsenceRepository absences;
    private final AbsenceReasonRepository reasons;
    private final ChildRepository children;
    private final ChildDocumentRepository documents;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public AbsenceService(ChildAbsenceRepository absences, AbsenceReasonRepository reasons, ChildRepository children,
                          ChildDocumentRepository documents, JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.absences = absences;
        this.reasons = reasons;
        this.children = children;
        this.documents = documents;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AbsenceDto> listForChild(UUID childId) {
        var child = loadChild(childId);
        return toDtos(absences.findByChildIdOrderByFromDayDesc(childId), Map.of(child.getId(), child));
    }

    /** Absences qui chevauchent une période (Présences : « absents prévenus » du jour). */
    @Transactional(readOnly = true)
    public List<AbsenceDto> listBetween(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || to.isAfter(from.plusDays(92))) {
            throw ApiException.rule("absence_period", "Choisissez une période de trois mois au plus.");
        }
        var structureId = CurrentUser.get().structureId();
        var byId = children.findByStructureId(structureId).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        return toDtos(absences.overlapping(structureId, from, to), byId);
    }

    @Transactional
    public AbsenceDto create(UUID childId, AbsenceRequest request) {
        var child = loadChild(childId);
        var user = CurrentUser.get();
        var absence = new ChildAbsence(child.getStructureId(), childId, user.displayName(), Instant.now(clock));
        var reason = apply(child, absence, request);
        audit.record(AuditAction.ABSENCE_RECORDED, "child_absence", absence.getId(), details(child, absence, reason));
        return toDtos(List.of(absence), Map.of(childId, child)).getFirst();
    }

    @Transactional
    public AbsenceDto update(UUID id, AbsenceRequest request) {
        var absence = load(id);
        var child = loadChild(absence.getChildId());
        requireNotInvoiced(child, absence.getFromDay(), absence.getToDay());
        var reason = apply(child, absence, request);
        audit.record(AuditAction.ABSENCE_UPDATED, "child_absence", id, details(child, absence, reason));
        return toDtos(List.of(absence), Map.of(child.getId(), child)).getFirst();
    }

    @Transactional
    public void delete(UUID id) {
        var absence = load(id);
        var child = loadChild(absence.getChildId());
        requireNotInvoiced(child, absence.getFromDay(), absence.getToDay());
        absences.delete(absence);
        var reason = reasons.findById(absence.getReasonId()).orElseThrow();
        audit.record(AuditAction.ABSENCE_DELETED, "child_absence", id, details(child, absence, reason));
    }

    private AbsenceReason apply(Child child, ChildAbsence absence, AbsenceRequest r) {
        var reason = reasons.findByIdAndStructureId(r.reasonId(), child.getStructureId())
                .orElseThrow(() -> ApiException.notFound("Motif d’absence introuvable."));
        if (!reason.isActive() && !reason.getId().equals(absence.getReasonId())) {
            throw ApiException.rule("absence_reason_inactive", "Ce motif d’absence n’est plus proposé.");
        }
        if (r.notifiedOn().isAfter(LocalDate.now(clock))) {
            throw ApiException.rule("absence_notified_future", "La date de prévenance ne peut pas être dans le futur.");
        }
        if (r.documentId() != null) {
            documents.findByIdAndStructureId(r.documentId(), child.getStructureId())
                    .filter(d -> d.getChildId().equals(child.getId()))
                    .orElseThrow(() -> ApiException.rule("absence_document",
                            "Le justificatif doit être un document du dossier de l’enfant."));
        }
        requireNotInvoiced(child, r.fromDay(), r.toDay());
        boolean overlap = absences.findByChildIdOrderByFromDayDesc(child.getId()).stream()
                .filter(o -> !o.getId().equals(absence.getId()))
                .anyMatch(o -> !o.getFromDay().isAfter(r.toDay()) && !o.getToDay().isBefore(r.fromDay()));
        if (overlap) {
            throw ApiException.rule("absence_overlap", "Une absence est déjà signalée sur une partie de ces jours.");
        }
        absence.update(r.fromDay(), r.toDay(), reason.getId(), r.notifiedOn(), r.documentId(),
                r.note() == null || r.note().isBlank() ? null : r.note().trim());
        absences.saveAndFlush(absence);
        return reason;
    }

    /** Refuse de toucher une absence d'un mois facturé (facture émise et non annulée par un avoir total). */
    private void requireNotInvoiced(Child child, LocalDate from, LocalDate to) {
        var invoiced = invoicedMonths(child.getFamilyId(), YearMonth.from(from), YearMonth.from(to));
        if (!invoiced.isEmpty()) {
            throw ApiException.rule("absence_month_invoiced",
                    "Le mois %s est déjà facturé : l’absence ne peut plus changer. Faites un avoir si besoin."
                            .formatted(Formats.monthYear(invoiced.getFirst())));
        }
    }

    private List<YearMonth> invoicedMonths(UUID familyId, YearMonth from, YearMonth to) {
        if (familyId == null) {
            return List.of();
        }
        return jdbc.queryForList("""
                        SELECT DISTINCT i.month FROM invoice i
                        WHERE i.family_id = ? AND i.month BETWEEN ? AND ?
                          AND NOT EXISTS (SELECT 1 FROM credit_note c WHERE c.invoice_id = i.id AND c.full_cancel)
                        ORDER BY i.month""",
                        LocalDate.class, familyId, from.atDay(1), to.atDay(1))
                .stream().map(YearMonth::from).toList();
    }

    private List<AbsenceDto> toDtos(Collection<ChildAbsence> list, Map<UUID, Child> childrenById) {
        if (list.isEmpty()) {
            return List.of();
        }
        var structureId = list.iterator().next().getStructureId();
        var reasonsById = reasons.findByStructureIdOrderByDisplayOrderAscLabelAsc(structureId).stream()
                .collect(Collectors.toMap(AbsenceReason::getId, Function.identity()));
        var documentNames = documents.findByChildIdIn(list.stream().map(ChildAbsence::getChildId).toList()).stream()
                .collect(Collectors.toMap(ChildDocument::getId, ChildDocument::getName));
        return list.stream()
                .filter(a -> childrenById.containsKey(a.getChildId()))
                .map(a -> {
                    var child = childrenById.get(a.getChildId());
                    var reason = reasonsById.get(a.getReasonId());
                    boolean invoiced = !invoicedMonths(child.getFamilyId(), YearMonth.from(a.getFromDay()),
                            YearMonth.from(a.getToDay())).isEmpty();
                    return new AbsenceDto(a.getId(), a.getChildId(), child.displayName(), a.getFromDay(),
                            a.getToDay(), a.getReasonId(), reason.getLabel(), reason.isDeductible(),
                            reason.isProofRequired(), a.getNotifiedOn(), a.getDocumentId(),
                            a.getDocumentId() == null ? null : documentNames.get(a.getDocumentId()), a.getNote(),
                            a.getRecordedBy(), invoiced, a.getVersion());
                })
                .toList();
    }

    /** Le motif (« Maladie », « Hospitalisation »…) est une donnée de santé : seul son identifiant est journalisé. */
    private static Map<String, Object> details(Child child, ChildAbsence absence, AbsenceReason reason) {
        return Map.of("child", child.displayName(), "from", absence.getFromDay().toString(),
                "to", absence.getToDay().toString(), "reasonId", reason.getId().toString());
    }

    private ChildAbsence load(UUID id) {
        return absences.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Absence introuvable."));
    }

    private Child loadChild(UUID childId) {
        return children.findByIdAndStructureId(childId, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Enfant introuvable."));
    }
}
