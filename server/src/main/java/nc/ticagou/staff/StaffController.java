package nc.ticagou.staff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import nc.ticagou.staff.StaffDtos.AbsenceDto;
import nc.ticagou.staff.StaffDtos.AbsenceRequest;
import nc.ticagou.staff.StaffDtos.AlertDto;
import nc.ticagou.staff.StaffDtos.Forecast;
import nc.ticagou.staff.StaffDtos.MonthSheet;
import nc.ticagou.staff.StaffDtos.OverrideRequest;
import nc.ticagou.staff.StaffDtos.ProfileDto;
import nc.ticagou.staff.StaffDtos.ProfileRequest;
import nc.ticagou.staff.StaffDtos.QualificationDto;
import nc.ticagou.staff.StaffDtos.QualificationRequest;
import nc.ticagou.staff.StaffDtos.ScheduleDto;
import nc.ticagou.staff.StaffDtos.ScheduleRequest;
import nc.ticagou.staff.StaffDtos.SettingsDto;
import nc.ticagou.staff.StaffDtos.ShiftDto;
import nc.ticagou.staff.StaffDtos.TeamOverview;
import nc.ticagou.staff.StaffDtos.TrainingDto;
import nc.ticagou.staff.StaffDtos.TrainingRequest;
import nc.ticagou.staff.StaffDtos.WeekPlanning;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;

/**
 * Personnel (maquette « Droits par rôle », ligne « Personnel et plannings ») : la direction modifie, la comptabilité
 * consulte et exporte les heures, les éducatrices et la cuisine voient leur planning et leurs heures.
 */
@RestController
@RequiresModule(LicenseModule.PERSONNEL)
@RequestMapping("/api/v1/staff")
@Tag(name = "Personnel", description = "Équipe, fiches, qualifications, planning, feuilles d'heures")
public class StaffController {

    static final String EDITORS = "hasRole('DIRECTION')";
    static final String READERS = "hasAnyRole('DIRECTION', 'COMPTABILITE')";

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final StaffService staff;
    private final PlanningService planning;
    private final TimesheetService timesheets;

    public StaffController(StaffService staff, PlanningService planning, TimesheetService timesheets) {
        this.staff = staff;
        this.planning = planning;
        this.timesheets = timesheets;
    }

    // ---------------------------------------------------------------- équipe et fiches

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "Équipe : personnes, contrats, alertes et chiffres clés")
    public TeamOverview team() {
        return staff.team();
    }

    @GetMapping("/alerts")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Qualifications expirées ou à renouveler, contrats qui se terminent")
    public List<AlertDto> alerts() {
        return staff.alerts();
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    @Operation(summary = "Fiche d'une personne (consultation tracée)")
    public ProfileDto profile(@PathVariable UUID id) {
        return staff.profile(id);
    }

    @PutMapping("/{id}/profile")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Modifier l'identité et le contrat de travail")
    public ProfileDto updateProfile(@PathVariable UUID id, @Valid @RequestBody ProfileRequest request) {
        return staff.updateProfile(id, request);
    }

    @PutMapping("/{id}/schedule")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Semaine type à partir d'une date")
    public ScheduleDto schedule(@PathVariable UUID id, @Valid @RequestBody ScheduleRequest request) {
        return planning.setSchedule(id, request);
    }

    @PostMapping("/{id}/qualifications")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter une qualification")
    public QualificationDto addQualification(@PathVariable UUID id, @Valid @RequestBody QualificationRequest request) {
        return staff.addQualification(id, request);
    }

    @PutMapping("/qualifications/{id}")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Modifier une qualification")
    public QualificationDto updateQualification(@PathVariable UUID id, @Valid @RequestBody QualificationRequest request) {
        return staff.updateQualification(id, request);
    }

    @PutMapping(path = "/qualifications/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDITORS)
    @Operation(summary = "Joindre le justificatif d'une qualification (PDF ou image, chiffré)")
    public QualificationDto attachQualification(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        return staff.attachQualification(id, file.getBytes(), file.getOriginalFilename());
    }

    @GetMapping("/qualifications/{id}/file")
    @PreAuthorize(READERS)
    @Operation(summary = "Justificatif d'une qualification")
    public ResponseEntity<byte[]> qualificationFile(@PathVariable UUID id) {
        return file(staff.qualificationFile(id));
    }

    @DeleteMapping("/qualifications/{id}")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une qualification et son justificatif")
    public void deleteQualification(@PathVariable UUID id) {
        staff.deleteQualification(id);
    }

    @PostMapping("/{id}/trainings")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter une formation prévue ou suivie")
    public TrainingDto addTraining(@PathVariable UUID id, @Valid @RequestBody TrainingRequest request) {
        return staff.addTraining(id, request);
    }

    @PutMapping("/trainings/{id}")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Modifier une formation")
    public TrainingDto updateTraining(@PathVariable UUID id, @Valid @RequestBody TrainingRequest request) {
        return staff.updateTraining(id, request);
    }

    @PutMapping(path = "/trainings/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDITORS)
    @Operation(summary = "Joindre l'attestation d'une formation")
    public TrainingDto attachTraining(@PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        return staff.attachTraining(id, file.getBytes(), file.getOriginalFilename());
    }

    @GetMapping("/trainings/{id}/file")
    @PreAuthorize(READERS)
    @Operation(summary = "Attestation d'une formation")
    public ResponseEntity<byte[]> trainingFile(@PathVariable UUID id) {
        return file(staff.trainingFile(id));
    }

    @DeleteMapping("/trainings/{id}")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une formation")
    public void deleteTraining(@PathVariable UUID id) {
        staff.deleteTraining(id);
    }

    @GetMapping("/settings")
    @PreAuthorize(READERS)
    @Operation(summary = "Réglages du module : délai d'alerte, pause par défaut, durée de référence")
    public SettingsDto settings() {
        return staff.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Modifier les réglages (nouvelle version datée du jour)")
    public SettingsDto updateSettings(@Valid @RequestBody SettingsDto request) {
        return staff.updateSettings(request);
    }

    // ---------------------------------------------------------------- planning

    @GetMapping("/planning")
    @PreAuthorize(READERS)
    @Operation(summary = "Planning de l'équipe pour la semaine contenant la date")
    public WeekPlanning planning(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
        return planning.week(week, false);
    }

    @GetMapping("/planning/me")
    @Operation(summary = "Mon planning de la semaine contenant la date")
    public WeekPlanning myPlanning(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
        return planning.week(week, true);
    }

    @PutMapping("/{id}/shifts/{day}")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Changer le poste d'un jour (horaire, section, repos)")
    public ShiftDto setShift(@PathVariable UUID id, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                             @Valid @RequestBody OverrideRequest request) {
        return planning.setOverride(id, day, request);
    }

    @DeleteMapping("/{id}/shifts/{day}")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Revenir à la semaine type pour ce jour")
    public ShiftDto resetShift(@PathVariable UUID id, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return planning.resetOverride(id, day);
    }

    @GetMapping("/forecast")
    @PreAuthorize(READERS)
    @Operation(summary = "Encadrement prévisionnel d'une journée, par créneau de 30 minutes")
    public Forecast forecast(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return planning.forecast(day);
    }

    @GetMapping("/absences")
    @PreAuthorize(READERS)
    @Operation(summary = "Absences du personnel sur une période")
    public List<AbsenceDto> absences(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return planning.absences(from, to);
    }

    @PostMapping("/absences")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Saisir une absence (congés, maladie, formation…)")
    public AbsenceDto createAbsence(@Valid @RequestBody AbsenceRequest request) {
        return planning.createAbsence(request);
    }

    @PutMapping("/absences/{id}")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Modifier une absence")
    public AbsenceDto updateAbsence(@PathVariable UUID id, @Valid @RequestBody AbsenceRequest request) {
        return planning.updateAbsence(id, request);
    }

    @DeleteMapping("/absences/{id}")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une absence")
    public void deleteAbsence(@PathVariable UUID id) {
        planning.deleteAbsence(id);
    }

    // ---------------------------------------------------------------- feuilles d'heures

    public record ReopenRequest(@NotBlank @Size(max = 300) String reason) {
    }

    @GetMapping("/timesheets/{month}")
    @PreAuthorize(READERS)
    @Operation(summary = "Feuilles d'heures du mois (AAAA-MM)")
    public MonthSheet timesheet(@PathVariable YearMonth month) {
        return timesheets.month(month, false);
    }

    @GetMapping("/timesheets/me/{month}")
    @Operation(summary = "Mes heures du mois")
    public MonthSheet myTimesheet(@PathVariable YearMonth month) {
        return timesheets.month(month, true);
    }

    @PostMapping("/timesheets/{month}/validate")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Valider le mois : la feuille est figée pour la paie")
    public MonthSheet validate(@PathVariable YearMonth month) {
        return timesheets.validate(month);
    }

    @PostMapping("/timesheets/{month}/reopen")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Rouvrir un mois validé (motif tracé)")
    public MonthSheet reopen(@PathVariable YearMonth month, @Valid @RequestBody ReopenRequest request) {
        return timesheets.reopen(month, request.reason());
    }

    @GetMapping("/timesheets/{month}/export.csv")
    @PreAuthorize(READERS)
    @Operation(summary = "Export CSV pour la paie (mois validé)")
    public ResponseEntity<byte[]> csv(@PathVariable YearMonth month) {
        return attachment(timesheets.csv(month), MediaType.parseMediaType("text/csv;charset=UTF-8"));
    }

    @GetMapping("/timesheets/{month}/export.xlsx")
    @PreAuthorize(READERS)
    @Operation(summary = "Export Excel pour la paie (mois validé)")
    public ResponseEntity<byte[]> xlsx(@PathVariable YearMonth month) {
        return attachment(timesheets.xlsx(month), XLSX);
    }

    private static ResponseEntity<byte[]> attachment(TimesheetService.Export export, MediaType type) {
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(export.content());
    }

    private static ResponseEntity<byte[]> file(StaffService.Download download) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(download.content());
    }
}
