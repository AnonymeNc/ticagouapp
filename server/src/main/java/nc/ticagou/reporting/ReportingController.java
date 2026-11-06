package nc.ticagou.reporting;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;
import nc.ticagou.reporting.ReportingExports.Export;
import nc.ticagou.reporting.ReportingService.Report;

/** Statistiques : bilan d'activité d'un mois ou d'une année (direction et comptabilité). */
@RestController
@RequestMapping("/api/v1/reporting")
@Tag(name = "Statistiques", description = "Fréquentation, occupation, absences, facturation et aides")
@PreAuthorize("hasAnyRole('DIRECTION', 'COMPTABILITE')")
@RequiresModule(LicenseModule.FACTURATION)
public class ReportingController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportingService reporting;
    private final ReportingExports exports;

    public ReportingController(ReportingService reporting, ReportingExports exports) {
        this.reporting = reporting;
        this.exports = exports;
    }

    @GetMapping
    @Operation(summary = "Bilan d'activité : « month » absent pour l'année civile")
    public Report report(@RequestParam @Min(2000) @Max(2100) int year, @RequestParam(required = false) @Min(1) @Max(12) Integer month) {
        return reporting.report(year, month);
    }

    @GetMapping("/pdf")
    @Operation(summary = "Bilan d'activité en PDF")
    public ResponseEntity<byte[]> pdf(@RequestParam int year, @RequestParam(required = false) Integer month) {
        return file(exports.pdf(year, month), MediaType.APPLICATION_PDF);
    }

    @GetMapping("/xlsx")
    @Operation(summary = "Bilan d'activité au format Excel")
    public ResponseEntity<byte[]> xlsx(@RequestParam int year, @RequestParam(required = false) Integer month) {
        return file(exports.xlsx(year, month), XLSX);
    }

    private static ResponseEntity<byte[]> file(Export export, MediaType type) {
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(export.content());
    }
}
