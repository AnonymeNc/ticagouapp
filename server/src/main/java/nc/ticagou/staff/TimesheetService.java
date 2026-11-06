package nc.ticagou.staff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.dhatim.fastexcel.Workbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.attendance.StaffAttendance;
import nc.ticagou.attendance.StaffAttendanceRepository;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.AppUser;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;
import nc.ticagou.staff.Shifts.AbsenceKind;
import nc.ticagou.staff.StaffDtos.DayDto;
import nc.ticagou.staff.StaffDtos.MonthSheet;
import nc.ticagou.staff.StaffDtos.PersonSheet;
import nc.ticagou.staff.StaffDtos.PunchDto;
import nc.ticagou.staff.StaffDtos.WeekDto;
import nc.ticagou.staff.TimesheetCalculator.DayInput;
import nc.ticagou.staff.TimesheetCalculator.Input;
import nc.ticagou.staff.TimesheetCalculator.Punch;

/**
 * Feuilles d'heures mensuelles pour la paie : prévu, pointé (pause déduite), absences, heures au-delà du contrat.
 * La direction valide le mois : la feuille est alors figée (copie JSON) et c'est elle qui est exportée.
 */
@Service
public class TimesheetService {

    static final Map<AbsenceKind, String> ABSENCE_LABELS = Map.of(AbsenceKind.CONGES, "Congés",
            AbsenceKind.MALADIE, "Maladie", AbsenceKind.FORMATION, "Formation", AbsenceKind.RECUPERATION, "Récupération",
            AbsenceKind.AUTRE, "Autre absence");

    private final PlanningService planning;
    private final StaffAttendanceRepository attendances;
    private final StaffProfileRepository profiles;
    private final TimesheetMonthRepository validated;
    private final StaffSettingsLookup settings;
    private final ObjectMapper json;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;

    public TimesheetService(PlanningService planning, StaffAttendanceRepository attendances,
                            StaffProfileRepository profiles, TimesheetMonthRepository validated,
                            StaffSettingsLookup settings, ObjectMapper json, AuditService audit, Clock clock) {
        this.planning = planning;
        this.attendances = attendances;
        this.profiles = profiles;
        this.validated = validated;
        this.settings = settings;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
        this.zone = clock.getZone();
    }

    /** Feuille du mois : figée si le mois est validé, sinon calculée. {@code onlyMe} : ses propres heures. */
    @Transactional(readOnly = true)
    public MonthSheet month(YearMonth month, boolean onlyMe) {
        var current = CurrentUser.get();
        var frozen = validated.findByStructureIdAndMonth(current.structureId(), month.atDay(1));
        var sheet = frozen.map(this::read).orElseGet(() -> compute(current.structureId(), month));
        if (!onlyMe) {
            return sheet;
        }
        return new MonthSheet(sheet.month(), sheet.validated(), sheet.validatedAt(), sheet.validatedBy(),
                sheet.people().stream().filter(p -> p.userId().equals(current.userId())).toList());
    }

    MonthSheet compute(UUID structureId, YearMonth month) {
        var from = TimesheetCalculator.firstDay(month);
        var to = TimesheetCalculator.rangeEnd(month);
        var today = LocalDate.now(clock);
        var staff = planning.staff(structureId);
        var shifts = planning.shifts(structureId, staff, from, to);
        var punches = attendances.findByStructureIdAndDayBetween(structureId, from, to).stream()
                .sorted(Comparator.comparing(StaffAttendance::getArrivedAt))
                .collect(Collectors.groupingBy(StaffAttendance::getUserId));
        var profileById = profiles.findAllById(staff.stream().map(AppUser::getId).toList()).stream()
                .collect(Collectors.toMap(StaffProfile::getUserId, Function.identity()));
        int defaultBreak = settings.on(structureId, month.atDay(1)).getDefaultBreakMinutes();
        var people = new ArrayList<PersonSheet>();
        for (var user : staff) {
            var byDay = punches.getOrDefault(user.getId(), List.of()).stream()
                    .collect(Collectors.groupingBy(StaffAttendance::getDay));
            var days = shifts.get(user.getId()).stream()
                    .map(s -> new DayInput(s, byDay.getOrDefault(s.day(), List.of()).stream()
                            .map(a -> new Punch(a.getArrivedAt().atZone(zone).toLocalTime().withSecond(0).withNano(0),
                                    a.getDepartedAt() == null ? null
                                            : a.getDepartedAt().atZone(zone).toLocalTime().withSecond(0).withNano(0)))
                            .toList()))
                    .toList();
            var profile = profileById.get(user.getId());
            Integer weekly = profile == null ? Integer.valueOf(39 * 60) : profile.getWeeklyMinutes();
            var r = TimesheetCalculator.compute(new Input(month, weekly, defaultBreak, today, days));
            boolean nothing = r.plannedMinutes() == 0 && r.workedMinutes() == 0 && r.absenceMinutes().isEmpty();
            if (nothing) {
                continue;
            }
            people.add(new PersonSheet(user.getId(), user.displayName(), user.getJobTitle(),
                    profile == null ? StaffProfile.ContractType.CDI : profile.getContractType(), weekly,
                    r.plannedMinutes(), r.workedMinutes(), r.overtimeMinutes(), r.absenceMinutes(), r.anomalies(),
                    r.days().stream().map(d -> new DayDto(d.day(), d.plannedMinutes(), d.workedMinutes(), d.absence(),
                            d.absenceMinutes(), d.punches().stream().map(p -> new PunchDto(p.arrivedAt(), p.departedAt())).toList(),
                            d.anomalies())).toList(),
                    r.weeks().stream().map(w -> new WeekDto(w.monday(), w.workedMinutes(), w.trainingMinutes(),
                            w.contractMinutes(), w.overtimeMinutes())).toList()));
        }
        return new MonthSheet(month, false, null, null, people);
    }

    /** Validation par la direction : refusée tant qu'il reste des anomalies ou que le mois n'est pas terminé. */
    @Transactional
    public MonthSheet validate(YearMonth month) {
        var user = CurrentUser.get();
        if (!month.isBefore(YearMonth.now(clock))) {
            throw ApiException.rule("timesheet_month_open", "Le mois n’est pas terminé : il se valide à partir du 1er du mois suivant.");
        }
        if (validated.findByStructureIdAndMonth(user.structureId(), month.atDay(1)).isPresent()) {
            throw ApiException.conflict("timesheet_validated", "Ce mois est déjà validé.");
        }
        var sheet = compute(user.structureId(), month);
        int anomalies = sheet.people().stream().mapToInt(PersonSheet::anomalies).sum();
        if (anomalies > 0) {
            throw ApiException.rule("timesheet_anomalies",
                    "%d anomalie%s à corriger avant de valider (pointages ou absences).".formatted(anomalies, anomalies > 1 ? "s" : ""));
        }
        var now = Instant.now(clock);
        var frozen = new MonthSheet(month, true, now, user.displayName(), sheet.people());
        validated.saveAndFlush(new TimesheetMonth(user.structureId(), month, now, user.displayName(), write(frozen)));
        audit.record(AuditAction.TIMESHEET_VALIDATED, "timesheet_month", null,
                Map.of("month", month.toString(), "people", sheet.people().size()));
        return frozen;
    }

    @Transactional
    public MonthSheet reopen(YearMonth month, String reason) {
        var user = CurrentUser.get();
        var frozen = validated.findByStructureIdAndMonth(user.structureId(), month.atDay(1))
                .orElseThrow(() -> ApiException.notFound("Ce mois n’est pas validé."));
        validated.delete(frozen);
        validated.flush();
        audit.record(AuditAction.TIMESHEET_REOPENED, "timesheet_month", frozen.getId(),
                Map.of("month", month.toString(), "reason", reason.trim()));
        return compute(user.structureId(), month);
    }

    // ---------------------------------------------------------------- exports

    public record Export(byte[] content, String fileName) {
    }

    /** CSV pour la paie (UTF-8 avec BOM, point-virgule : ouvert tel quel par Excel). Mois validé seulement. */
    @Transactional
    public Export csv(YearMonth month) {
        var sheet = frozen(month);
        var out = new StringBuilder("﻿");
        out.append("Personne;Fonction;Contrat;Date;Prévu (h);Pointé (h);Absence;Absence (h)\r\n");
        for (var p : sheet.people()) {
            for (var d : p.days()) {
                out.append(String.join(";", cell(p.name()), cell(p.jobTitle()), p.contractType().name(),
                        Formats.date(d.day()), hours(d.plannedMinutes()), hours(d.workedMinutes()),
                        d.absence() == null ? "" : ABSENCE_LABELS.get(d.absence()), hours(d.absenceMinutes()))).append("\r\n");
            }
        }
        out.append("\r\nPersonne;Prévu (h);Pointé (h);Au-delà du contrat (h)");
        for (var kind : AbsenceKind.values()) {
            out.append(';').append(ABSENCE_LABELS.get(kind)).append(" (h)");
        }
        out.append("\r\n");
        for (var p : sheet.people()) {
            out.append(String.join(";", cell(p.name()), hours(p.plannedMinutes()), hours(p.workedMinutes()),
                    hours(p.overtimeMinutes())));
            for (var kind : AbsenceKind.values()) {
                out.append(';').append(hours(p.absenceMinutes().getOrDefault(kind, 0)));
            }
            out.append("\r\n");
        }
        exported(month, "csv");
        return new Export(out.toString().getBytes(StandardCharsets.UTF_8), "heures-%s.csv".formatted(month));
    }

    /** Classeur Excel : synthèse du mois, puis une ligne par personne et par jour. Mois validé seulement. */
    @Transactional
    public Export xlsx(YearMonth month) {
        var sheet = frozen(month);
        var out = new ByteArrayOutputStream();
        try {
            var workbook = new Workbook(out, "Ti'Cagou", "1.0");
            var summary = workbook.newWorksheet("Synthèse");
            summary.value(0, 0, "Feuilles d’heures · " + Formats.monthYear(month));
            summary.style(0, 0).bold().fontSize(13).set();
            summary.value(1, 0, "Validées le %s par %s".formatted(Formats.date(sheet.validatedAt().atZone(zone).toLocalDate()),
                    sheet.validatedBy()));
            var titles = new ArrayList<>(List.of("Personne", "Fonction", "Contrat", "Durée hebdo (h)", "Prévu (h)",
                    "Pointé (h)", "Au-delà du contrat (h)"));
            for (var kind : AbsenceKind.values()) {
                titles.add(ABSENCE_LABELS.get(kind) + " (h)");
            }
            for (int c = 0; c < titles.size(); c++) {
                summary.value(3, c, titles.get(c));
            }
            summary.range(3, 0, 3, titles.size() - 1).style().bold().fillColor("DCEFEE").set();
            int r = 4;
            for (var p : sheet.people()) {
                summary.value(r, 0, p.name());
                summary.value(r, 1, p.jobTitle());
                summary.value(r, 2, p.contractType().name());
                if (p.weeklyMinutes() != null) {
                    summary.value(r, 3, p.weeklyMinutes() / 60.0);
                }
                summary.value(r, 4, p.plannedMinutes() / 60.0);
                summary.value(r, 5, p.workedMinutes() / 60.0);
                summary.value(r, 6, p.overtimeMinutes() / 60.0);
                int c = 7;
                for (var kind : AbsenceKind.values()) {
                    summary.value(r, c++, p.absenceMinutes().getOrDefault(kind, 0) / 60.0);
                }
                summary.range(r, 3, r, titles.size() - 1).style().format("0.00").set();
                r++;
            }
            var detail = workbook.newWorksheet("Détail par jour");
            String[] head = {"Personne", "Date", "Prévu (h)", "Pointé (h)", "Pointages", "Absence", "Absence (h)"};
            for (int c = 0; c < head.length; c++) {
                detail.value(0, c, head[c]);
            }
            detail.range(0, 0, 0, head.length - 1).style().bold().fillColor("DCEFEE").set();
            int row = 1;
            for (var p : sheet.people()) {
                for (var d : p.days()) {
                    detail.value(row, 0, p.name());
                    detail.value(row, 1, d.day());
                    detail.style(row, 1).format("dd/mm/yyyy").set();
                    detail.value(row, 2, d.plannedMinutes() / 60.0);
                    detail.value(row, 3, d.workedMinutes() / 60.0);
                    detail.value(row, 4, String.join(", ", d.punches().stream()
                            .map(x -> Formats.time(x.arrivedAt()) + "–" + Formats.time(x.departedAt())).toList()));
                    if (d.absence() != null) {
                        detail.value(row, 5, ABSENCE_LABELS.get(d.absence()));
                        detail.value(row, 6, d.absenceMinutes() / 60.0);
                    }
                    detail.range(row, 2, row, 3).style().format("0.00").set();
                    row++;
                }
            }
            workbook.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        exported(month, "xlsx");
        return new Export(out.toByteArray(), "heures-%s.xlsx".formatted(month));
    }

    private MonthSheet frozen(YearMonth month) {
        return validated.findByStructureIdAndMonth(CurrentUser.get().structureId(), month.atDay(1)).map(this::read)
                .orElseThrow(() -> ApiException.rule("timesheet_not_validated",
                        "Validez d’abord les heures de %s : seul un mois validé s’exporte pour la paie."
                                .formatted(Formats.monthYear(month))));
    }

    private void exported(YearMonth month, String format) {
        audit.record(AuditAction.TIMESHEET_EXPORTED, "timesheet_month", null, Map.of("month", month.toString(), "format", format));
    }

    private static String hours(int minutes) {
        return "%d,%02d".formatted(minutes / 60, Math.round((minutes % 60) * 100 / 60.0));
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        // Une cellule qui commence par = + - @ serait lue comme une formule par le tableur.
        var safe = (value.matches("^[=+\\-@].*") ? "'" + value : value).replace("\"", "\"\"");
        return safe.contains(";") || safe.contains("\"") ? "\"" + safe + "\"" : safe;
    }

    private String write(MonthSheet sheet) {
        try {
            return json.writeValueAsString(sheet);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MonthSheet read(TimesheetMonth month) {
        try {
            return json.readValue(month.getSnapshot(), MonthSheet.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
