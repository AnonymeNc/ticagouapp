package nc.ticagou.staff;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.AppUser;
import nc.ticagou.auth.AppUserRepository;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.document.Uploads;
import nc.ticagou.staff.StaffDtos.AlertDto;
import nc.ticagou.staff.StaffDtos.AlertKind;
import nc.ticagou.staff.StaffDtos.MemberSummary;
import nc.ticagou.staff.StaffDtos.ProfileDto;
import nc.ticagou.staff.StaffDtos.ProfileRequest;
import nc.ticagou.staff.StaffDtos.QualificationDto;
import nc.ticagou.staff.StaffDtos.QualificationRequest;
import nc.ticagou.staff.StaffDtos.SettingsDto;
import nc.ticagou.staff.StaffDtos.TeamOverview;
import nc.ticagou.staff.StaffDtos.TrainingDto;
import nc.ticagou.staff.StaffDtos.TrainingRequest;

/**
 * Équipe et fiches du personnel : une fiche par compte (créée à la première modification), qualifications et
 * formations avec justificatifs chiffrés, alertes d'expiration, paramètres du module. Les fiches contiennent des
 * données personnelles : chaque consultation est tracée.
 */
@Service
public class StaffService {

    private final AppUserRepository users;
    private final StaffProfileRepository profiles;
    private final StaffQualificationRepository qualifications;
    private final StaffTrainingRepository trainings;
    private final StaffSettingsRepository settings;
    private final StaffSettingsLookup settingsLookup;
    private final PlanningService planning;
    private final EncryptedFileStore fileStore;
    private final StoredFileRepository files;
    private final AuditService audit;
    private final Clock clock;

    public StaffService(AppUserRepository users, StaffProfileRepository profiles,
                        StaffQualificationRepository qualifications, StaffTrainingRepository trainings,
                        StaffSettingsRepository settings, StaffSettingsLookup settingsLookup, PlanningService planning,
                        EncryptedFileStore fileStore,
                        StoredFileRepository files, AuditService audit, Clock clock) {
        this.users = users;
        this.profiles = profiles;
        this.qualifications = qualifications;
        this.trainings = trainings;
        this.settings = settings;
        this.settingsLookup = settingsLookup;
        this.planning = planning;
        this.fileStore = fileStore;
        this.files = files;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- équipe

    @Transactional(readOnly = true)
    public TeamOverview team() {
        var structureId = CurrentUser.get().structureId();
        var today = LocalDate.now(clock);
        int alertDays = currentSettings(structureId).getAlertDays();
        var profileById = profiles.findByStructureId(structureId).stream()
                .collect(Collectors.toMap(StaffProfile::getUserId, Function.identity()));
        var qualificationsByUser = qualifications.findByStructureId(structureId).stream()
                .collect(Collectors.groupingBy(StaffQualification::getUserId));
        var members = users.findByStructureIdOrderByLastNameAscFirstNameAsc(structureId).stream()
                .filter(AppUser::isActive)
                .map(u -> summary(u, profileById.get(u.getId()), qualificationsByUser.getOrDefault(u.getId(), List.of()),
                        today, alertDays))
                .toList();
        var activeIds = members.stream().map(MemberSummary::userId).collect(Collectors.toSet());
        var states = qualificationsByUser.entrySet().stream().filter(e -> activeIds.contains(e.getKey()))
                .flatMap(e -> e.getValue().stream()).toList();
        int expired = (int) states.stream()
                .filter(q -> QualificationState.of(q.getValidUntil(), today, alertDays) == QualificationState.EXPIREE).count();
        var toRenew = states.stream()
                .filter(q -> QualificationState.of(q.getValidUntil(), today, alertDays) == QualificationState.A_RENOUVELER)
                .toList();
        var nextDue = toRenew.stream().map(StaffQualification::getValidUntil).min(Comparator.naturalOrder()).orElse(null);
        var planned = trainings.findByStructureId(structureId).stream()
                .filter(t -> !t.isDone() && !t.getEndsOn().isBefore(today) && activeIds.contains(t.getUserId()))
                .sorted(Comparator.comparing(StaffTraining::getStartsOn)).toList();
        return new TeamOverview(members, members.size(), (int) members.stream().filter(MemberSummary::countsInRatio).count(),
                expired, toRenew.size(), nextDue, planned.size(),
                planned.isEmpty() ? null : planned.getFirst().getLabel(),
                planned.isEmpty() ? null : planned.getFirst().getStartsOn(), alertDays);
    }

    /** Alertes de toute l'équipe (tableau de bord) : qualifications expirées ou à renouveler, contrats qui finissent. */
    @Transactional(readOnly = true)
    public List<AlertDto> alerts() {
        return team().members().stream().flatMap(m -> m.alerts().stream())
                .sorted(Comparator.comparing(AlertDto::date)).toList();
    }

    private MemberSummary summary(AppUser user, StaffProfile profile, List<StaffQualification> list, LocalDate today,
                                  int alertDays) {
        var p = profile != null ? profile : new StaffProfile(user.getId(), user.getStructureId());
        var name = user.displayName();
        var alerts = new ArrayList<AlertDto>();
        for (var q : list) {
            var state = QualificationState.of(q.getValidUntil(), today, alertDays);
            if (state.alert()) {
                alerts.add(new AlertDto(user.getId(), name, AlertKind.QUALIFICATION, state, q.getLabel(), q.getValidUntil()));
            }
        }
        if (p.getEndsOn() != null && !p.getEndsOn().isBefore(today) && ChronoUnit.DAYS.between(today, p.getEndsOn()) <= alertDays) {
            alerts.add(new AlertDto(user.getId(), name, AlertKind.CONTRAT, QualificationState.A_RENOUVELER,
                    "Fin de contrat", p.getEndsOn()));
        }
        alerts.sort(Comparator.comparing(AlertDto::date));
        return new MemberSummary(user.getId(), user.getFirstName(), user.getLastName(), user.getRole(), user.isActive(),
                user.getJobTitle(), p.getDiploma(), user.getHomeSectionId(), user.isCountsInRatio(), p.getContractType(),
                p.getWeeklyMinutes(), p.getHiredOn(), p.getEndsOn(), List.copyOf(alerts));
    }

    // ---------------------------------------------------------------- fiche

    @Transactional
    public ProfileDto profile(UUID userId) {
        var dto = profileDto(loadUser(userId));
        audit.record(AuditAction.STAFF_RECORD_VIEWED, "user", userId, Map.of());
        return dto;
    }

    @Transactional
    public ProfileDto updateProfile(UUID userId, ProfileRequest r) {
        var user = loadUser(userId);
        var profile = profiles.findById(userId).orElseGet(() -> new StaffProfile(userId, user.getStructureId()));
        profile.update(r.birthDate(), blank(r.phone()), blank(r.email()), blank(r.address()), blank(r.cafatNumber()),
                blank(r.emergencyContact()), blank(r.diploma()), r.contractType(), r.weeklyMinutes(), r.hiredOn(),
                r.endsOn());
        profiles.saveAndFlush(profile);
        audit.record(AuditAction.STAFF_PROFILE_UPDATED, "user", userId,
                Map.of("contractType", r.contractType().name()));
        return profileDto(user);
    }

    private ProfileDto profileDto(AppUser user) {
        var today = LocalDate.now(clock);
        int alertDays = currentSettings(user.getStructureId()).getAlertDays();
        var profile = profiles.findById(user.getId()).orElseGet(() -> new StaffProfile(user.getId(), user.getStructureId()));
        var quals = qualifications.findByUserIdOrderByValidUntilAsc(user.getId());
        var trainingList = trainings.findByUserIdOrderByStartsOnDesc(user.getId());
        var since = today.withDayOfYear(1).minusYears(1);
        int trainingMinutes = trainingList.stream().filter(t -> t.isDone() && !t.getStartsOn().isBefore(since))
                .mapToInt(StaffTraining::getMinutes).sum();
        return new ProfileDto(summary(user, profile, quals, today, alertDays), profile.getBirthDate(), profile.getPhone(),
                profile.getEmail(), profile.getAddress(), profile.getCafatNumber(), profile.getEmergencyContact(),
                quals.stream().map(q -> qualificationDto(q, today, alertDays)).toList(),
                trainingList.stream().map(StaffService::trainingDto).toList(), trainingMinutes,
                planning.scheduleOn(user.getId(), today), planning.upcomingSchedules(user.getId(), today),
                profile.getVersion());
    }

    // ---------------------------------------------------------------- qualifications

    @Transactional
    public QualificationDto addQualification(UUID userId, QualificationRequest r) {
        var user = loadUser(userId);
        var q = new StaffQualification(user.getStructureId(), userId, Instant.now(clock));
        q.update(r.kind(), r.label().trim(), r.obtainedOn(), r.validUntil(), r.verified());
        qualifications.saveAndFlush(q);
        audit.record(AuditAction.STAFF_QUALIFICATION_ADDED, "staff_qualification", q.getId(),
                Map.of("user", userId.toString(), "label", q.getLabel()));
        return qualificationDto(q);
    }

    @Transactional
    public QualificationDto updateQualification(UUID id, QualificationRequest r) {
        var q = loadQualification(id);
        q.update(r.kind(), r.label().trim(), r.obtainedOn(), r.validUntil(), r.verified());
        qualifications.saveAndFlush(q);
        audit.record(AuditAction.STAFF_QUALIFICATION_UPDATED, "staff_qualification", id, Map.of("label", q.getLabel()));
        return qualificationDto(q);
    }

    @Transactional
    public QualificationDto attachQualification(UUID id, byte[] content, String originalName) {
        var q = loadQualification(id);
        var previous = q.getFileId();
        var stored = store(q.getStructureId(), content);
        q.attach(stored, Uploads.safeFileName(originalName, "justificatif.pdf"));
        qualifications.saveAndFlush(q);
        drop(previous);
        audit.record(AuditAction.STAFF_QUALIFICATION_UPDATED, "staff_qualification", id,
                Map.of("label", q.getLabel(), "file", q.getFileName()));
        return qualificationDto(q);
    }

    @Transactional
    public void deleteQualification(UUID id) {
        var q = loadQualification(id);
        qualifications.delete(q);
        qualifications.flush();
        drop(q.getFileId());
        audit.record(AuditAction.STAFF_QUALIFICATION_DELETED, "staff_qualification", id, Map.of("label", q.getLabel()));
    }

    @Transactional(readOnly = true)
    public Download qualificationFile(UUID id) {
        var q = loadQualification(id);
        return download(q.getFileId(), q.getFileName());
    }

    private QualificationDto qualificationDto(StaffQualification q) {
        return qualificationDto(q, LocalDate.now(clock), currentSettings(q.getStructureId()).getAlertDays());
    }

    private static QualificationDto qualificationDto(StaffQualification q, LocalDate today, int alertDays) {
        return new QualificationDto(q.getId(), q.getKind(), q.getLabel(), q.getObtainedOn(), q.getValidUntil(),
                q.getFileName(), q.isVerified(), QualificationState.of(q.getValidUntil(), today, alertDays), q.getVersion());
    }

    private StaffQualification loadQualification(UUID id) {
        return qualifications.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Qualification introuvable."));
    }

    // ---------------------------------------------------------------- formations

    @Transactional
    public TrainingDto addTraining(UUID userId, TrainingRequest r) {
        var user = loadUser(userId);
        var t = new StaffTraining(user.getStructureId(), userId, Instant.now(clock));
        t.update(r.label().trim(), r.minutes(), r.startsOn(), r.endsOn(), blank(r.organization()), r.done());
        trainings.saveAndFlush(t);
        audit.record(AuditAction.STAFF_TRAINING_ADDED, "staff_training", t.getId(),
                Map.of("user", userId.toString(), "label", t.getLabel()));
        return trainingDto(t);
    }

    @Transactional
    public TrainingDto updateTraining(UUID id, TrainingRequest r) {
        var t = loadTraining(id);
        t.update(r.label().trim(), r.minutes(), r.startsOn(), r.endsOn(), blank(r.organization()), r.done());
        trainings.saveAndFlush(t);
        audit.record(AuditAction.STAFF_TRAINING_UPDATED, "staff_training", id, Map.of("label", t.getLabel()));
        return trainingDto(t);
    }

    @Transactional
    public TrainingDto attachTraining(UUID id, byte[] content, String originalName) {
        var t = loadTraining(id);
        var previous = t.getFileId();
        t.attach(store(CurrentUser.get().structureId(), content), Uploads.safeFileName(originalName, "attestation.pdf"));
        trainings.saveAndFlush(t);
        drop(previous);
        audit.record(AuditAction.STAFF_TRAINING_UPDATED, "staff_training", id,
                Map.of("label", t.getLabel(), "file", t.getFileName()));
        return trainingDto(t);
    }

    @Transactional
    public void deleteTraining(UUID id) {
        var t = loadTraining(id);
        trainings.delete(t);
        trainings.flush();
        drop(t.getFileId());
        audit.record(AuditAction.STAFF_TRAINING_DELETED, "staff_training", id, Map.of("label", t.getLabel()));
    }

    @Transactional(readOnly = true)
    public Download trainingFile(UUID id) {
        var t = loadTraining(id);
        return download(t.getFileId(), t.getFileName());
    }

    private static TrainingDto trainingDto(StaffTraining t) {
        return new TrainingDto(t.getId(), t.getLabel(), t.getMinutes(), t.getStartsOn(), t.getEndsOn(),
                t.getOrganization(), t.isDone(), t.getFileName(), t.getVersion());
    }

    private StaffTraining loadTraining(UUID id) {
        return trainings.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Formation introuvable."));
    }

    // ---------------------------------------------------------------- paramètres

    @Transactional(readOnly = true)
    public SettingsDto settings() {
        var s = currentSettings(CurrentUser.get().structureId());
        return new SettingsDto(s.getValidFrom(), s.getAlertDays(), s.getDefaultBreakMinutes(), s.getReferenceWeeklyMinutes());
    }

    /** Nouvelle version datée d'aujourd'hui (ou mise à jour de celle du jour) : les mois passés gardent leurs réglages. */
    @Transactional
    public SettingsDto updateSettings(SettingsDto r) {
        var structureId = CurrentUser.get().structureId();
        var today = LocalDate.now(clock);
        var s = settings.findByStructureIdOrderByValidFromDesc(structureId).stream()
                .filter(x -> x.getValidFrom().equals(today)).findFirst()
                .orElseGet(() -> new StaffSettings(structureId, today, Instant.now(clock)));
        s.update(r.alertDays(), r.defaultBreakMinutes(), r.referenceWeeklyMinutes());
        settings.saveAndFlush(s);
        audit.record(AuditAction.STAFF_SETTINGS_UPDATED, "staff_settings", null, Map.of("alertDays", r.alertDays(),
                "defaultBreakMinutes", r.defaultBreakMinutes(), "referenceWeeklyMinutes", r.referenceWeeklyMinutes()));
        return settings();
    }

    private StaffSettings currentSettings(UUID structureId) {
        return settingsLookup.on(structureId, LocalDate.now(clock));
    }

    // ---------------------------------------------------------------- outils

    public record Download(byte[] content, String contentType, String fileName) {
    }

    private UUID store(UUID structureId, byte[] content) {
        var type = Uploads.checkDocument(content);
        return fileStore.store(structureId, content, type).getId();
    }

    private void drop(UUID fileId) {
        if (fileId != null) {
            files.findById(fileId).ifPresent(fileStore::delete);
        }
    }

    private Download download(UUID fileId, String fileName) {
        if (fileId == null) {
            throw ApiException.notFound("Aucun justificatif joint.");
        }
        var file = files.findById(fileId).orElseThrow(() -> ApiException.notFound("Justificatif introuvable."));
        return new Download(fileStore.read(file), file.getContentType(), fileName);
    }

    AppUser loadUser(UUID userId) {
        return users.findByIdAndStructureId(userId, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Personne introuvable."));
    }

    static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
