package nc.ticagou.meal;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.meal.HaccpService.Kind;
import nc.ticagou.meal.HaccpService.Point;
import nc.ticagou.meal.HaccpService.Reading;

/** Relevés de températures HACCP (lot 4, module Repas). */
@RestController
@RequestMapping("/api/v1/haccp")
@Tag(name = "Repas", description = "Relevés de températures (HACCP)")
public class HaccpController {

    public record PointRequest(@NotNull @Size(max = 80) String label, @NotNull Kind kind, int minTenths, int maxTenths) {
    }

    public record ReadingRequest(@NotNull UUID pointId, int tenths, @Size(max = 300) String action) {
    }

    private static final String READERS = "hasAnyRole('DIRECTION', 'EDUCATRICE', 'CUISINE')";
    private static final String EDITORS = "hasAnyRole('DIRECTION', 'CUISINE')";

    private final HaccpService haccp;

    public HaccpController(HaccpService haccp) {
        this.haccp = haccp;
    }

    @GetMapping("/points")
    @PreAuthorize(READERS)
    @Operation(summary = "Points de contrôle et leurs seuils (dixièmes de degré)")
    public List<Point> points() {
        return haccp.points();
    }

    @PostMapping("/points")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter un point de contrôle")
    public Point add(@Valid @RequestBody PointRequest p) {
        return haccp.addPoint(p.label(), p.kind(), p.minTenths(), p.maxTenths());
    }

    @DeleteMapping("/points/{id}")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Ne plus relever ce point (les relevés restent)")
    public void deactivate(@PathVariable UUID id) {
        haccp.deactivate(id);
    }

    @PostMapping("/readings")
    @PreAuthorize(READERS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Noter un relevé ; hors seuil, mesure corrective obligatoire")
    public Reading record(@Valid @RequestBody ReadingRequest r) {
        return haccp.record(r.pointId(), r.tenths(), r.action());
    }

    @GetMapping("/readings")
    @PreAuthorize(READERS)
    @Operation(summary = "Relevés d'un mois")
    public List<Reading> month(@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return haccp.month(month);
    }

    @GetMapping("/readings/{month}.csv")
    @PreAuthorize(READERS)
    @Operation(summary = "Export du mois pour les contrôles (CSV)")
    public ResponseEntity<byte[]> csv(@PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("releves-temperatures-" + month + ".csv").build().toString())
                .body(haccp.csv(month));
    }
}
