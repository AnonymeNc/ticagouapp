package nc.ticagou.meal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.meal.MealDtos.AdjustmentRequest;
import nc.ticagou.meal.MealDtos.DietDto;
import nc.ticagou.meal.MealDtos.DietRequest;
import nc.ticagou.meal.MealDtos.KitchenDay;
import nc.ticagou.meal.MealDtos.MealSettings;
import nc.ticagou.meal.MealDtos.MenuWeekDto;
import nc.ticagou.meal.MealDtos.MenuWeekRequest;
import nc.ticagou.meal.MealDtos.NoteRequest;
import nc.ticagou.meal.MealDtos.QuarterDto;
import nc.ticagou.meal.MealDtos.QuarterSummary;
import nc.ticagou.meal.MealDtos.ReopenRequest;

/**
 * Repas (maquette Droits par rôle, ligne « Repas et menus ») : direction et cuisine modifient, éducatrices
 * consultent, comptabilité sans accès. Clôture et réouverture d'un trimestre : direction.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Repas", description = "Comptage des repas, vue cuisine, menus, récapitulatif trimestriel")
public class MealController {

    static final String READERS = "hasAnyRole('DIRECTION', 'EDUCATRICE', 'CUISINE')";
    static final String EDITORS = "hasAnyRole('DIRECTION', 'CUISINE')";

    private final MealService meals;
    private final MenuService menus;
    private final DietService diets;
    private final MealExports exports;
    private final ZoneId zone;
    private final Clock clock;

    public MealController(MealService meals, MenuService menus, DietService diets, MealExports exports,
                          TiCagouProperties properties, Clock clock) {
        this.meals = meals;
        this.menus = menus;
        this.diets = diets;
        this.exports = exports;
        this.zone = properties.zone();
        this.clock = clock;
    }

    private LocalDate orToday(LocalDate date) {
        return date != null ? date : LocalDate.now(clock.withZone(zone));
    }

    // --- Vue cuisine ---------------------------------------------------------------------------

    @GetMapping("/meals/kitchen")
    @PreAuthorize(READERS)
    @Operation(summary = "Vue cuisine d'un jour (aujourd'hui par défaut) : comptes, enfants à adapter, menu")
    public KitchenDay kitchen(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return meals.kitchen(orToday(date));
    }

    @PutMapping("/meals/adjustments")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Corriger le comptage d'un enfant (servi ou non, avec motif) ; vide = revenir au calcul")
    public KitchenDay adjust(@Valid @RequestBody AdjustmentRequest request) {
        return meals.adjust(request);
    }

    @PutMapping("/meals/notes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDITORS)
    @Operation(summary = "Consigne du jour de la cuisine pour un enfant")
    public void note(@Valid @RequestBody NoteRequest request) {
        meals.note(request);
    }

    @GetMapping("/meals/settings")
    @PreAuthorize(READERS)
    @Operation(summary = "Heures du repas et du goûter, étiquettes des plats")
    public MealSettings settings() {
        return diets.settings();
    }

    @PutMapping("/meals/settings")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Modifier les heures du repas et du goûter")
    public MealSettings updateSettings(@Valid @RequestBody MealSettings request) {
        return diets.updateSettings(request);
    }

    // --- Régimes -------------------------------------------------------------------------------

    @GetMapping("/diets")
    @PreAuthorize(READERS)
    @Operation(summary = "Régimes et ce qu'ils excluent, avec le nombre d'enfants inscrits qui les suivent")
    public List<DietDto> diets() {
        return diets.list();
    }

    @PostMapping("/diets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDITORS)
    @Operation(summary = "Ajouter un régime")
    public DietDto createDiet(@Valid @RequestBody DietRequest request) {
        return diets.create(request);
    }

    @PutMapping("/diets/{id}")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Modifier un régime")
    public DietDto updateDiet(@PathVariable UUID id, @Valid @RequestBody DietRequest request) {
        return diets.update(id, request);
    }

    @DeleteMapping("/diets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDITORS)
    @Operation(summary = "Supprimer un régime (les fiches gardent son libellé en texte libre)")
    public void deleteDiet(@PathVariable UUID id) {
        diets.delete(id);
    }

    // --- Menus ---------------------------------------------------------------------------------

    @GetMapping("/menus")
    @PreAuthorize(READERS)
    @Operation(summary = "Menus de la semaine qui contient la date (cette semaine par défaut)")
    public MenuWeekDto week(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return menus.week(orToday(date));
    }

    @PutMapping("/menus/{weekStart}")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Enregistrer les menus d'une semaine (lundi)")
    public MenuWeekDto saveWeek(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
                                @Valid @RequestBody MenuWeekRequest request) {
        return menus.save(weekStart, request);
    }

    @PostMapping("/menus/{weekStart}/copy-previous")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Copier les menus de la semaine précédente")
    public MenuWeekDto copyPrevious(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return menus.copyPrevious(weekStart);
    }

    // --- Récapitulatif trimestriel ---------------------------------------------------------------

    @GetMapping("/meals/quarters")
    @PreAuthorize(READERS)
    @Operation(summary = "Trimestre en cours et quatre précédents, avec leur état")
    public List<QuarterSummary> quarters() {
        return meals.quarters();
    }

    @GetMapping("/meals/quarters/{year}/{quarter}")
    @PreAuthorize(READERS)
    @Operation(summary = "Repas et goûters délivrés par mois et par section")
    public QuarterDto quarter(@PathVariable int year, @PathVariable int quarter) {
        return meals.quarter(year, quarter).summary();
    }

    @PostMapping("/meals/quarters/{year}/{quarter}/close")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Clore le trimestre : comptes figés, pointages et corrections refusés")
    public QuarterDto close(@PathVariable int year, @PathVariable int quarter) {
        return meals.close(year, quarter);
    }

    @PostMapping("/meals/quarters/{year}/{quarter}/reopen")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Rouvrir un trimestre clos (motif obligatoire, tracé)")
    public QuarterDto reopen(@PathVariable int year, @PathVariable int quarter, @Valid @RequestBody ReopenRequest request) {
        return meals.reopen(year, quarter, request.reason());
    }

    @GetMapping("/meals/quarters/{year}/{quarter}/pdf")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Récapitulatif signable d'une page (PDF)")
    public ResponseEntity<byte[]> pdf(@PathVariable int year, @PathVariable int quarter) {
        return download(exports.pdf(year, quarter), MediaType.APPLICATION_PDF);
    }

    @GetMapping("/meals/quarters/{year}/{quarter}/xlsx")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Récapitulatif et détail par enfant et par jour (Excel)")
    public ResponseEntity<byte[]> xlsx(@PathVariable int year, @PathVariable int quarter) {
        return download(exports.xlsx(year, quarter),
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    private static ResponseEntity<byte[]> download(MealExports.Export export, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(export.content());
    }
}
