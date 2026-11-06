package nc.ticagou.attendance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import nc.ticagou.attendance.AttendanceDtos.BatchResult;
import nc.ticagou.attendance.AttendanceDtos.Operation;
import nc.ticagou.attendance.AttendanceDtos.OperationResult;
import nc.ticagou.attendance.AttendanceDtos.OperationType;
import nc.ticagou.attendance.AttendanceDtos.StaffAction;
import nc.ticagou.attendance.AttendanceDtos.StaffPunchRequest;
import nc.ticagou.attendance.AttendanceDtos.StaffPunchResult;
import nc.ticagou.attendance.AttendanceService.Author;
import nc.ticagou.attendance.AttendanceService.Validation;
import nc.ticagou.attendance.KioskOperation.Outcome;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.auth.AppUser;
import nc.ticagou.auth.Role;
import nc.ticagou.auth.TokenService;
import nc.ticagou.common.ApiException;
import nc.ticagou.device.Device;
import nc.ticagou.device.DeviceService;
import nc.ticagou.journal.JournalService;

/**
 * Opérations des tablettes (ADR 0005). Chaque action de la tablette passe par sa file d'attente, en ligne
 * comme hors connexion ; le serveur les applique dans l'ordre, chacune dans sa transaction, une seule fois
 * par {@code clientId}. Un fait réel n'est jamais perdu : un code faux saisi hors connexion garde le pointage
 * et le signale à la direction.
 */
@Service
public class KioskService {

    /** Horloge d'une tablette : au-delà, l'opération est refusée plutôt que datée n'importe comment. */
    static final Duration MAX_CLOCK_AHEAD = Duration.ofMinutes(5);
    static final Duration MAX_AGE = Duration.ofDays(7);

    private final AttendanceService attendance;
    private final StaffAttendanceRepository staffAttendances;
    private final KioskOperationRepository operations;
    private final DeviceService devices;
    private final PinVerifier pins;
    private final TokenService tokens;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final JournalService journal;
    private final Clock clock;

    public KioskService(AttendanceService attendance, StaffAttendanceRepository staffAttendances,
                        KioskOperationRepository operations, DeviceService devices, PinVerifier pins,
                        TokenService tokens, AuditService audit, JournalService journal,
                        PlatformTransactionManager transactionManager,
                        Clock clock) {
        this.attendance = attendance;
        this.staffAttendances = staffAttendances;
        this.operations = operations;
        this.devices = devices;
        this.pins = pins;
        this.tokens = tokens;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.journal = journal;
        this.clock = clock;
    }

    /** Erreur d'une opération : refusée, ou « déjà reçue » quand l'effet existe déjà (arrivée en double). */
    private static final class Rejected extends RuntimeException {

        final Outcome outcome;
        final String code;

        Rejected(Outcome outcome, String code, String message) {
            super(message);
            this.outcome = outcome;
            this.code = code;
        }
    }

    /** Résultat d'une opération appliquée : à vérifier par la direction ou non. */
    private record Applied(boolean needsReview, UUID userId, UUID childId) {
    }

    public BatchResult process(java.util.List<Operation> batch) {
        var device = devices.requireCurrent();
        var results = new ArrayList<OperationResult>();
        for (var op : batch) {
            results.add(processOne(device, op, null));
        }
        return new BatchResult(clock.instant(), results);
    }

    /**
     * Photo prise sur la tablette : envoyée seule, en ligne (trop lourde pour la file hors connexion), avec son
     * opération TRANSMISSION de type PHOTO. Idempotente par {@code clientId} comme les autres opérations.
     */
    public OperationResult processPhoto(Operation op, byte[] photo) {
        if (op.type() != OperationType.TRANSMISSION || op.entry() == null
                || op.entry().kind() != nc.ticagou.journal.DailyEntry.Kind.PHOTO) {
            throw ApiException.rule("invalid_operation", "Une photo s’envoie avec une transmission « Photo ».");
        }
        return processOne(devices.requireCurrent(), op, photo);
    }

    private OperationResult processOne(Device device, Operation op, byte[] photo) {
        var known = operations.findById(op.clientId());
        if (known.isPresent()) {
            return new OperationResult(op.clientId(), Outcome.DEJA_RECUE, "duplicate", known.get().getMessage(), false);
        }
        var now = clock.instant();
        try {
            var applied = transactions.execute(status -> {
                var result = apply(device, op, now, photo);
                operations.save(new KioskOperation(op.clientId(), device.getStructureId(), device.getId(),
                        op.type().name(), result.userId(), result.childId(), op.occurredAt(), now, Outcome.APPLIQUEE,
                        result.needsReview() ? "needs_review" : null, null));
                return result;
            });
            return new OperationResult(op.clientId(), Outcome.APPLIQUEE, null, null, applied.needsReview());
        } catch (Rejected e) {
            return record(device, op, now, e.outcome, e.code, e.getMessage());
        } catch (ApiException e) {
            return record(device, op, now, Outcome.REFUSEE, e.code(), e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // Même clientId reçu en parallèle, ou contrainte (passage déjà ouvert) : sans effet.
            if (operations.existsById(op.clientId())) {
                return new OperationResult(op.clientId(), Outcome.DEJA_RECUE, "duplicate", null, false);
            }
            return record(device, op, now, Outcome.REFUSEE, "conflict", "Opération en conflit avec un autre pointage.");
        }
    }

    private OperationResult record(Device device, Operation op, Instant now, Outcome outcome, String code, String message) {
        transactions.executeWithoutResult(status -> {
            operations.save(new KioskOperation(op.clientId(), device.getStructureId(), device.getId(), op.type().name(),
                    op.userId(), op.childId(), op.occurredAt(), now, outcome, code, message));
            if (op.type() == OperationType.CONNEXION_HORS_LIGNE && "bad_pin".equals(code)) {
                var user = pins.find(device.getStructureId(), op.userId());
                audit.record(new Actor(device.getStructureId(), op.userId(), user.map(AppUser::displayName).orElse(null)),
                        AuditAction.KIOSK_PIN_MISMATCH, "user", op.userId(), Map.of("usage", "connexion hors ligne"));
            } else if (outcome == Outcome.REFUSEE) {
                audit.record(new Actor(device.getStructureId(), op.userId(), device.getName()),
                        AuditAction.KIOSK_OPERATION_REFUSED, "kiosk_operation", op.clientId(),
                        Map.of("type", op.type().name(), "code", String.valueOf(code)));
            }
        });
        return new OperationResult(op.clientId(), outcome, code, message, false);
    }

    private Applied apply(Device device, Operation op, Instant now, byte[] photo) {
        var structureId = device.getStructureId();
        if (op.occurredAt().isAfter(now.plus(MAX_CLOCK_AHEAD)) || op.occurredAt().isBefore(now.minus(MAX_AGE))) {
            throw new Rejected(Outcome.REFUSEE, "invalid_time", "Heure de la tablette incohérente : vérifiez sa date et son heure.");
        }
        var at = op.occurredAt().isAfter(now) ? now : op.occurredAt();
        return switch (op.type()) {
            case CONNEXION_HORS_LIGNE -> offlineLogin(structureId, op);
            case POINTAGE_PERSONNEL -> staffPunchOffline(structureId, op, at);
            default -> childOrSection(structureId, op, at, photo);
        };
    }

    /** Connexion faite sans le serveur : le code est vérifié maintenant, les opérations suivantes s'y réfèrent. */
    private Applied offlineLogin(UUID structureId, Operation op) {
        require(op.userId() != null && op.pin() != null, "Connexion incomplète.");
        if (!pins.matches(structureId, op.userId(), op.pin())) {
            // Tracé par record(), hors de la transaction annulée.
            throw new Rejected(Outcome.REFUSEE, "bad_pin", "Connexion hors ligne avec un code incorrect.");
        }
        return new Applied(false, op.userId(), null);
    }

    private Applied childOrSection(UUID structureId, Operation op, Instant at, byte[] photo) {
        var author = author(structureId, op);
        var day = attendance.dayOf(at);
        switch (op.type()) {
            case ARRIVEE -> {
                require(op.childId() != null, "Enfant manquant.");
                try {
                    var created = attendance.arrive(structureId, op.attendanceId(), op.childId(), op.sectionId(), at,
                            author.author(), ChildAttendance.Source.TABLETTE);
                    if (author.unverified()) {
                        created.flag(UNVERIFIED);
                    }
                } catch (ApiException e) {
                    if ("already_present".equals(e.code())) {
                        throw new Rejected(Outcome.DEJA_RECUE, e.code(), e.getMessage());
                    }
                    throw e;
                }
            }
            case ANNULER_ARRIVEE -> {
                var open = attendance.findOpen(structureId, op.attendanceId(), op.childId(), day)
                        .orElseThrow(() -> ApiException.rule("not_present", "Aucune arrivée à annuler."));
                attendance.cancelArrival(structureId, open.getId(), author.author());
            }
            case CONSIGNES -> {
                var target = attendance.findOpen(structureId, op.attendanceId(), op.childId(), day)
                        .orElseThrow(() -> ApiException.rule("not_present", "L’enfant n’est pas pointé présent."));
                attendance.setInstructions(structureId, target.getId(), op.droppedOffBy(), op.instructions(),
                        author.author());
            }
            case MEDICAMENT_DONNE -> {
                require(op.instructionId() != null, "Médicament manquant.");
                attendance.medicationGiven(structureId, op.instructionId(), at, author.author());
            }
            case DEPART -> {
                return new Applied(depart(structureId, op, at, day, author), author.author().userId(), op.childId());
            }
            case TRANSMISSION -> {
                require(op.entry() != null, "Transmission manquante.");
                journal.record(structureId, op.clientId(), op.entry(), at, photo, author.author());
                return new Applied(author.unverified(), author.author().userId(), null);
            }
            case SECTION_PERSONNEL -> {
                require(op.sectionId() != null && op.staffUserId() != null, "Section ou personne manquante.");
                attendance.staffMove(structureId, op.staffUserId(), op.sectionId(), at, author.author());
            }
            default -> throw ApiException.rule("invalid_operation", "Opération inconnue.");
        }
        return new Applied(author.unverified(), author.author().userId(), op.childId());
    }

    private boolean depart(UUID structureId, Operation op, Instant at, java.time.LocalDate day, ResolvedAuthor author) {
        var open = attendance.findOpen(structureId, op.attendanceId(), op.childId(), day)
                .orElseThrow(() -> ApiException.rule("not_present",
                        "Départ reçu pour un enfant qui n’est pas pointé présent : à vérifier."));
        Validation validation = null;
        boolean review = author.unverified();
        if (op.override() != null) {
            require(op.override().directorId() != null && op.override().directorPin() != null,
                    "La validation de la direction manque.");
            if (op.offline()) {
                boolean ok = pins.matches(structureId, op.override().directorId(), op.override().directorPin());
                var director = pins.find(structureId, op.override().directorId())
                        .filter(u -> u.getRole() == Role.DIRECTION)
                        .orElseThrow(() -> ApiException.rule("validator_unknown",
                                "Seule une personne de la direction peut valider ce départ."));
                if (!ok) {
                    audit.record(new Actor(structureId, director.getId(), director.displayName()),
                            AuditAction.KIOSK_PIN_MISMATCH, "child_attendance", open.getId(),
                            Map.of("usage", "validation de départ hors ligne"));
                    review = true;
                }
                validation = new Validation(director.getId(), director.displayName(), ok);
            } else {
                var director = pins.verify(structureId, op.override().directorId(), op.override().directorPin(),
                        Set.of(Role.DIRECTION));
                validation = new Validation(director.getId(), director.displayName(), true);
            }
        }
        var departed = attendance.depart(structureId, open, at, op.pickup(), op.override(), validation, author.author());
        if (author.unverified()) {
            departed.flag(UNVERIFIED);
        }
        return review;
    }

    private static final String UNVERIFIED = "Pointé hors connexion par une session au code incorrect";

    /** Auteur prouvé ; {@code unverified} si la connexion hors ligne avait un code faux. */
    private record ResolvedAuthor(Author author, boolean unverified) {
    }

    private ResolvedAuthor author(UUID structureId, Operation op) {
        require(op.userId() != null, "Auteur manquant.");
        var user = pins.find(structureId, op.userId())
                .orElseThrow(() -> new Rejected(Outcome.REFUSEE, "unknown_author", "Auteur inconnu ou désactivé."));
        if (!AttendanceService.pointingRoles().contains(user.getRole())) {
            throw new Rejected(Outcome.REFUSEE, "forbidden", "Le pointage est réservé à la direction et aux éducatrices.");
        }
        if (op.sessionId() != null && tokens.isSessionOf(op.sessionId(), user.getId())) {
            return new ResolvedAuthor(Author.of(user), false);
        }
        if (op.offlineSessionId() != null) {
            var login = operations.findById(op.offlineSessionId())
                    .filter(o -> OperationType.CONNEXION_HORS_LIGNE.name().equals(o.getType())
                            && user.getId().equals(o.getUserId()))
                    .orElseThrow(() -> new Rejected(Outcome.REFUSEE, "unknown_author",
                            "Connexion hors ligne introuvable : renvoyez-la avant ses pointages."));
            return new ResolvedAuthor(Author.of(user), login.getOutcome() != Outcome.APPLIQUEE);
        }
        throw new Rejected(Outcome.REFUSEE, "unknown_author", "Auteur non prouvé (session inconnue).");
    }

    /** Pointage du personnel fait hors connexion : nom puis code, vérifié à la réception. */
    private Applied staffPunchOffline(UUID structureId, Operation op, Instant at) {
        require(op.userId() != null && op.pin() != null && op.staffAction() != null, "Pointage incomplet.");
        var user = pins.find(structureId, op.userId())
                .orElseThrow(() -> new Rejected(Outcome.REFUSEE, "unknown_author", "Personne inconnue ou désactivée."));
        boolean verified = pins.matches(structureId, user.getId(), op.pin());
        var day = attendance.dayOf(at);
        var open = staffAttendances.findByUserIdAndDayAndDepartedAtIsNull(user.getId(), day);
        StaffAttendance target;
        if (op.staffAction() == StaffAction.ARRIVEE) {
            if (open.isPresent()) {
                throw new Rejected(Outcome.DEJA_RECUE, "already_present", "%s est déjà présent(e).".formatted(user.getFirstName()));
            }
            var source = op.offline() ? StaffAttendance.Source.PIN_HORS_LIGNE : StaffAttendance.Source.PIN;
            target = attendance.staffArrive(structureId, op.attendanceId(), user, op.sectionId(), at, source);
        } else {
            target = open.orElseThrow(() -> ApiException.rule("not_present",
                    "%s n’a pas d’arrivée enregistrée : départ à vérifier.".formatted(user.getFirstName())));
            target = attendance.staffDepart(structureId, target, at, user);
        }
        if (!verified) {
            target.pinMismatch("Code incorrect saisi hors connexion");
            staffAttendances.save(target);
            audit.record(new Actor(structureId, user.getId(), user.displayName()), AuditAction.KIOSK_PIN_MISMATCH,
                    "staff_attendance", target.getId(), Map.of("usage", "pointage du personnel"));
        }
        return new Applied(!verified, user.getId(), null);
    }

    /**
     * Pointage du personnel en ligne, par le seul code : bascule arrivée / départ. Sans section habituelle,
     * l'arrivée demande la section ({@code section_required}). Un {@code clientId} déjà reçu renvoie l'état actuel.
     */
    @Transactional
    public StaffPunchResult staffPunch(StaffPunchRequest request) {
        var device = devices.requireCurrent();
        var structureId = device.getStructureId();
        var now = clock.instant();
        var user = pins.identify(structureId, device.getId(), request.pin());
        var day = attendance.dayOf(now);
        var open = staffAttendances.findByUserIdAndDayAndDepartedAtIsNull(user.getId(), day);
        if (operations.existsById(request.clientId())) {
            var last = open.orElse(null);
            return new StaffPunchResult(user.getId(), user.displayName(),
                    last != null ? StaffAction.ARRIVEE : StaffAction.DEPART, now,
                    last == null ? null : last.getSectionId(), last == null ? null : last.getId());
        }
        StaffPunchResult result;
        if (open.isPresent()) {
            var departed = attendance.staffDepart(structureId, open.get(), now, user);
            result = new StaffPunchResult(user.getId(), user.displayName(), StaffAction.DEPART, now,
                    departed.getSectionId(), departed.getId());
        } else {
            var section = request.sectionId() != null ? request.sectionId() : user.getHomeSectionId();
            if (section == null) {
                throw ApiException.rule("section_required", "Dans quelle section commencez-vous ?");
            }
            var arrived = attendance.staffArrive(structureId, request.clientId(), user, section, now,
                    StaffAttendance.Source.PIN);
            result = new StaffPunchResult(user.getId(), user.displayName(), StaffAction.ARRIVEE, now,
                    arrived.getSectionId(), arrived.getId());
        }
        operations.save(new KioskOperation(request.clientId(), structureId, device.getId(),
                OperationType.POINTAGE_PERSONNEL.name(), user.getId(), null, now, now, Outcome.APPLIQUEE, null, null));
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new Rejected(Outcome.REFUSEE, "invalid_operation", message);
        }
    }
}
