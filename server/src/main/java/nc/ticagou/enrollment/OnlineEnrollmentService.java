package nc.ticagou.enrollment;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.AddressThrottle;
import nc.ticagou.common.ApiException;
import nc.ticagou.enrollment.EnrollmentRequest.CareType;
import nc.ticagou.enrollment.EnrollmentDtos.RequestForm;
import nc.ticagou.structure.StructureRepository;

/**
 * Pré-inscription en ligne (lot 3, ADR 0020) : formulaire public de la crèche, questions personnalisées, anti-abus sans
 * CAPTCHA, conservation limitée des demandes sans suite.
 */
@Service
public class OnlineEnrollmentService {

    public enum QuestionKind { TEXTE, OUI_NON, CHOIX }

    public record Question(UUID id, String label, QuestionKind kind, List<String> options, boolean required,
                           int displayOrder, LocalDate validFrom, LocalDate validTo) {
    }

    public record Criterion(UUID id, String label) {
    }

    public record Settings(boolean enabled, String intro) {
    }

    /** Ce que voit une famille : la crèche, son texte d'accueil, les critères et les questions en vigueur. */
    public record PublicForm(boolean enabled, String structureName, String intro, List<Criterion> criteria,
                             List<Question> questions) {
    }

    /** Demande publique : le formulaire du bureau, les réponses, le consentement, et les deux pièges à robots. */
    public record Submission(RequestForm request, Map<UUID, String> answers, boolean consent, String website,
                             Long startedAt) {
    }

    static final Duration MIN_FILL_TIME = Duration.ofSeconds(5);
    static final int MAX_PER_DAY = 30;
    static final java.time.Period RETENTION = java.time.Period.ofMonths(12);
    private static final Logger log = LoggerFactory.getLogger(OnlineEnrollmentService.class);

    /** 5 demandes par heure et par adresse IP. */
    static final class SubmissionThrottle extends AddressThrottle {
        SubmissionThrottle(Clock clock) {
            super(clock, 5, Duration.ofHours(1));
        }
    }

    private final JdbcTemplate jdbc;
    private final EnrollmentService enrollments;
    private final StructureRepository structures;
    private final AuditService audit;
    private final Clock clock;
    private final boolean scheduling;
    private final SubmissionThrottle throttle;

    public OnlineEnrollmentService(JdbcTemplate jdbc, EnrollmentService enrollments, StructureRepository structures,
                                   AuditService audit, Clock clock,
                                   @Value("${ticagou.scheduling.enabled:true}") boolean scheduling) {
        this.jdbc = jdbc;
        this.enrollments = enrollments;
        this.structures = structures;
        this.audit = audit;
        this.clock = clock;
        this.scheduling = scheduling;
        this.throttle = new SubmissionThrottle(clock);
    }

    /** Pour les tests : oublie les envois comptés par adresse. */
    void resetThrottle() {
        throttle.reset();
    }

    // --- Public -------------------------------------------------------------------------------

    /** Une instance par crèche (ADR 0013) : la structure de l'instance. */
    private UUID structureId() {
        return structures.findAll().stream().findFirst().orElseThrow(() -> ApiException.notFound("Crèche introuvable.")).getId();
    }

    @Transactional(readOnly = true)
    public PublicForm publicForm() {
        var structureId = structureId();
        var settings = settings(structureId);
        var name = structures.findById(structureId).map(s -> s.getName()).orElse("");
        if (!settings.enabled()) {
            return new PublicForm(false, name, null, List.of(), List.of());
        }
        var today = LocalDate.now(clock);
        var criteria = jdbc.query("SELECT id, label FROM enrollment_criterion WHERE structure_id = ? AND valid_from <= ? "
                        + "AND (valid_to IS NULL OR valid_to >= ?) ORDER BY display_order, label",
                (rs, i) -> new Criterion(rs.getObject(1, UUID.class), rs.getString(2)), structureId, Date.valueOf(today), Date.valueOf(today));
        return new PublicForm(true, name, settings.intro(), criteria, questions(structureId, today));
    }

    /** Vrai si la demande est enregistrée ; une demande écartée par l'anti-abus reçoit la même réponse. */
    @Transactional
    public boolean submit(Submission s, String address) {
        var structureId = structureId();
        if (!settings(structureId).enabled()) {
            throw ApiException.rule("online_closed", "La crèche ne reçoit pas de pré-inscription en ligne pour le moment.");
        }
        if (!s.consent()) {
            throw ApiException.rule("consent_required", "Cochez la case de consentement pour envoyer votre demande.");
        }
        var now = clock.instant();
        boolean robot = (s.website() != null && !s.website().isBlank()) || s.startedAt() == null
                || Instant.ofEpochMilli(s.startedAt()).plus(MIN_FILL_TIME).isAfter(now);
        boolean flood = throttle.isBlocked(address) || todayCount(structureId) >= MAX_PER_DAY;
        throttle.failed(address);
        if (robot || flood) {
            log.info("Pré-inscription en ligne écartée ({})", robot ? "robot" : "trop de demandes");
            return false;
        }
        var today = LocalDate.now(clock);
        var questions = questions(structureId, today);
        var answers = new ArrayList<Object[]>();
        for (var q : questions) {
            var raw = s.answers() == null ? null : s.answers().get(q.id());
            var value = raw == null ? "" : raw.strip();
            if (value.isEmpty()) {
                if (q.required()) {
                    throw ApiException.rule("answer_required", "Répondez à la question : " + q.label());
                }
                continue;
            }
            if (value.length() > 1000 || (q.kind() == QuestionKind.OUI_NON && !Set.of("Oui", "Non").contains(value))
                    || (q.kind() == QuestionKind.CHOIX && !q.options().contains(value))) {
                throw ApiException.rule("answer_invalid", "Réponse invalide à la question : " + q.label());
            }
            answers.add(new Object[] {q.id(), q.label(), value});
        }
        var form = s.request();
        // Le formulaire public ne choisit ni la famille ni la section : la direction les fixe.
        var clean = new RequestForm(form.childFirstName(), form.childLastName(), form.birthDate(), form.expectedBirthDate(),
                null, form.desiredStart(), form.careType() == null ? CareType.REGULIER : form.careType(), form.arrivesAt(),
                form.leavesAt(), form.days(), form.guardianFirstName(), form.guardianLastName(), form.relationship(),
                form.phone(), form.email(), null, form.criteria() == null ? Set.of() : form.criteria());
        var id = enrollments.createOnline(structureId, clean, now);
        for (var a : answers) {
            jdbc.update("INSERT INTO enrollment_answer (request_id, question_id, label, value) VALUES (?, ?, ?, ?)",
                    id, a[0], a[1], a[2]);
        }
        return true;
    }

    private int todayCount(UUID structureId) {
        return jdbc.queryForObject("SELECT count(*) FROM enrollment_request WHERE structure_id = ? AND source = 'EN_LIGNE' "
                + "AND received_on = ?", Integer.class, structureId, Date.valueOf(LocalDate.now(clock)));
    }

    // --- Réglages (direction) -----------------------------------------------------------------

    public Settings settings() {
        return settings(CurrentUser.get().structureId());
    }

    private Settings settings(UUID structureId) {
        return jdbc.query("SELECT enabled, intro FROM online_enrollment_settings WHERE structure_id = ?",
                (rs, i) -> new Settings(rs.getBoolean(1), rs.getString(2)), structureId).stream().findFirst()
                .orElse(new Settings(false, null));
    }

    @Transactional
    public Settings updateSettings(Settings settings) {
        var structureId = CurrentUser.get().structureId();
        var intro = settings.intro() == null || settings.intro().isBlank() ? null : settings.intro().strip();
        if (intro != null && intro.length() > 1000) {
            throw ApiException.rule("intro_length", "Le texte d’accueil est limité à 1 000 caractères.");
        }
        jdbc.update("INSERT INTO online_enrollment_settings (structure_id, enabled, intro, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (structure_id) DO UPDATE SET enabled = excluded.enabled, intro = excluded.intro, "
                        + "updated_at = excluded.updated_at", structureId, settings.enabled(), intro, Timestamp.from(clock.instant()));
        audit.record(AuditAction.ONLINE_ENROLLMENT_SETTINGS_UPDATED, "structure", structureId, Map.of("enabled", settings.enabled()));
        return settings(structureId);
    }

    public List<Question> allQuestions() {
        return jdbc.query("SELECT id, label, kind, options, required, display_order, valid_from, valid_to FROM enrollment_question "
                + "WHERE structure_id = ? ORDER BY display_order, label", (rs, i) -> question(rs), CurrentUser.get().structureId());
    }

    @Transactional
    public Question addQuestion(String label, QuestionKind kind, List<String> options, boolean required, int displayOrder) {
        var structureId = CurrentUser.get().structureId();
        var clean = label == null ? "" : label.strip();
        if (clean.isEmpty() || clean.length() > 200) {
            throw ApiException.rule("question_label", "Écrivez la question (200 caractères au plus).");
        }
        var opts = options == null ? List.<String>of() : options.stream().map(String::strip).filter(o -> !o.isEmpty()).distinct().toList();
        if (kind == QuestionKind.CHOIX && opts.size() < 2) {
            throw ApiException.rule("question_options", "Proposez au moins deux choix.");
        }
        if (opts.stream().anyMatch(o -> o.contains("|")) || String.join("|", opts).length() > 500) {
            throw ApiException.rule("question_options", "Les choix sont trop longs ou contiennent « | ».");
        }
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO enrollment_question (id, structure_id, label, kind, options, required, display_order, valid_from, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, structureId, clean, kind.name(),
                kind == QuestionKind.CHOIX ? String.join("|", opts) : null, required, displayOrder,
                Date.valueOf(LocalDate.now(clock)), Timestamp.from(clock.instant()));
        audit.record(AuditAction.ENROLLMENT_QUESTION_CHANGED, "enrollment_question", id, Map.of("action", "added"));
        return allQuestions().stream().filter(q -> q.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Une question cesse d'être posée dès aujourd'hui. Elle n'est effacée que si personne n'y a encore répondu : sinon
     * les réponses gardent son libellé et elle reste dans l'historique, close la veille.
     */
    @Transactional
    public void retireQuestion(UUID id) {
        var structureId = CurrentUser.get().structureId();
        var today = LocalDate.now(clock);
        boolean answered = jdbc.queryForObject("SELECT count(*) FROM enrollment_answer WHERE question_id = ?", Long.class, id) > 0;
        int changed = answered
                ? jdbc.update("UPDATE enrollment_question SET valid_from = LEAST(valid_from, ?), valid_to = ? WHERE id = ? "
                        + "AND structure_id = ?", Date.valueOf(today.minusDays(1)), Date.valueOf(today.minusDays(1)), id, structureId)
                : jdbc.update("DELETE FROM enrollment_question WHERE id = ? AND structure_id = ?", id, structureId);
        if (changed == 0) {
            throw ApiException.notFound("Question introuvable.");
        }
        audit.record(AuditAction.ENROLLMENT_QUESTION_CHANGED, "enrollment_question", id, Map.of("action", "retired"));
    }

    private List<Question> questions(UUID structureId, LocalDate day) {
        return jdbc.query("SELECT id, label, kind, options, required, display_order, valid_from, valid_to FROM enrollment_question "
                        + "WHERE structure_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?) ORDER BY display_order, label",
                (rs, i) -> question(rs), structureId, Date.valueOf(day), Date.valueOf(day));
    }

    private static Question question(java.sql.ResultSet rs) throws java.sql.SQLException {
        var options = rs.getString("options");
        var validTo = rs.getDate("valid_to");
        return new Question(rs.getObject("id", UUID.class), rs.getString("label"), QuestionKind.valueOf(rs.getString("kind")),
                options == null ? List.of() : Arrays.asList(options.split("\\|")), rs.getBoolean("required"),
                rs.getInt("display_order"), rs.getDate("valid_from").toLocalDate(), validTo == null ? null : validTo.toLocalDate());
    }

    // --- Conservation -------------------------------------------------------------------------

    @Scheduled(cron = "0 15 4 * * *", zone = "Pacific/Noumea")
    public void scheduledPurge() {
        if (scheduling) {
            purge();
        }
    }

    /**
     * Demandes refusées ou restées sans suite (« Nouvelle », « À recontacter ») depuis 12 mois : effacées, avec leurs
     * notes, leur historique et leurs réponses, sauf si un enfant ou un contrat en est issu (ADR 0020).
     */
    @Transactional
    public int purge() {
        var limit = Date.valueOf(LocalDate.now(clock).minus(RETENTION));
        var ids = jdbc.queryForList("SELECT r.id FROM enrollment_request r WHERE r.stage IN ('REFUSEE', 'NOUVELLE', 'A_RECONTACTER') "
                + "AND r.stage_changed_on < ? AND r.child_id IS NULL AND NOT EXISTS (SELECT 1 FROM contract c WHERE c.request_id = r.id)",
                UUID.class, limit);
        var byStructure = new HashMap<UUID, Integer>();
        for (var id : ids) {
            jdbc.update("DELETE FROM enrollment_note WHERE request_id = ?", id);
            jdbc.update("DELETE FROM enrollment_event WHERE request_id = ?", id);
            var structureId = jdbc.queryForObject("DELETE FROM enrollment_request WHERE id = ? RETURNING structure_id", UUID.class, id);
            byStructure.merge(structureId, 1, Integer::sum);
        }
        byStructure.forEach((structureId, count) -> audit.record(new AuditService.Actor(structureId, null, "Conservation"),
                AuditAction.ENROLLMENT_REQUESTS_PURGED, "structure", structureId, Map.of("count", count)));
        return ids.size();
    }
}
