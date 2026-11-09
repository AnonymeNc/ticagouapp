package nc.ticagou.console.reporting;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import nc.ticagou.console.audit.AuditService;

/** Reporting et exports pour le comptable ; mêmes filtres partout : du, au (AAAA-MM-JJ), client, formule. */
@RestController
@RequestMapping("/api/v1/reporting")
class ReportingController {

    record SettingsRequest(@Min(value = 0, message = "Taux horaire positif.") @Max(1_000_000) long supportHourlyRate,
                           @Min(value = 0, message = "Hébergement positif.") @Max(1_000_000) long hostingPerCreche) {
    }

    private final ReportingService service;
    private final AuditService audit;

    ReportingController(ReportingService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    ReportingService.Report report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
                                   @RequestParam(required = false) UUID client, @RequestParam(required = false) String formule) {
        return service.report(service.filter(du, au, client, formule));
    }

    @GetMapping("/journal-ventes.csv")
    ResponseEntity<byte[]> sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
                                 @RequestParam(required = false) UUID client, @RequestParam(required = false) String formule) {
        var f = service.filter(du, au, client, formule);
        audit.record("EXPORT_COMPTABLE", "journal-ventes", Map.of("du", f.from().toString(), "au", f.to().toString()));
        return file(ReportingExports.salesCsv(service.sales(f)), "journal-ventes-%s-%s.csv".formatted(f.from(), f.to()), "text/csv");
    }

    @GetMapping("/encaissements.csv")
    ResponseEntity<byte[]> receipts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
                                    @RequestParam(required = false) UUID client, @RequestParam(required = false) String formule) {
        var f = service.filter(du, au, client, formule);
        audit.record("EXPORT_COMPTABLE", "encaissements", Map.of("du", f.from().toString(), "au", f.to().toString()));
        return file(ReportingExports.receiptsCsv(service.receipts(f)), "encaissements-%s-%s.csv".formatted(f.from(), f.to()), "text/csv");
    }

    @GetMapping("/reporting.xlsx")
    void excel(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
               @RequestParam(required = false) UUID client, @RequestParam(required = false) String formule,
               HttpServletResponse response) throws IOException {
        var f = service.filter(du, au, client, formule);
        var report = service.report(f);
        audit.record("EXPORT_COMPTABLE", "reporting-excel", Map.of("du", f.from().toString(), "au", f.to().toString()));
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("reporting-%s-%s.xlsx".formatted(f.from(), f.to())).build().toString());
        ReportingExports.workbook(report, service.sales(f), service.receipts(f), response.getOutputStream());
    }

    @PutMapping("/reglages")
    ReportingService.Settings settings(@Valid @RequestBody SettingsRequest request) {
        return service.updateSettings(request.supportHourlyRate(), request.hostingPerCreche());
    }

    private static ResponseEntity<byte[]> file(byte[] content, String name, String type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.parseMediaType(type + ";charset=UTF-8")).body(content);
    }
}
