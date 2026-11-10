package nc.ticagou.accounting;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import nc.ticagou.accounting.AccountingExporter.Check;
import nc.ticagou.accounting.AccountingExporter.Format;
import nc.ticagou.accounting.AccountingSettingsService.AccountDto;
import nc.ticagou.accounting.AccountingSettingsService.AccountRequest;
import nc.ticagou.accounting.AccountingSettingsService.SettingsDto;

/** Export comptable (Facturation › Export comptable) et ses paramètres : direction et comptabilité. */
@RestController
@RequestMapping("/api/v1/accounting")
@Tag(name = "Comptabilité", description = "Écritures exportées vers le logiciel du cabinet comptable (CSV, FEC)")
@PreAuthorize("hasAnyRole('DIRECTION', 'COMPTABILITE')")
public class AccountingController {

    private final AccountingExporter exporter;
    private final AccountingSettingsService settings;

    public AccountingController(AccountingExporter exporter, AccountingSettingsService settings) {
        this.exporter = exporter;
        this.settings = settings;
    }

    @GetMapping("/check")
    @Operation(summary = "Contrôle avant export : totaux par journal, anomalies")
    public Check check(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return exporter.check(from, to);
    }

    @GetMapping("/export")
    @Operation(summary = "Écritures de la période au format CSV paramétrable ou FEC")
    public ResponseEntity<byte[]> export(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                         @RequestParam(defaultValue = "CSV") Format format) {
        var file = exporter.export(from, to, format);
        var type = format == Format.CSV ? new MediaType("text", "csv", StandardCharsets.UTF_8)
                : new MediaType("text", "plain", StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }

    @GetMapping("/accounts")
    @Operation(summary = "Plan de comptes daté, par rôle")
    public List<AccountDto> accounts() {
        return settings.accounts();
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Nouveau compte pour un rôle, à partir d'une date")
    public AccountDto create(@Valid @RequestBody AccountRequest request) {
        return settings.create(request);
    }

    @PutMapping("/accounts/{id}")
    @Operation(summary = "Corriger un compte (dates, numéro, libellé)")
    public AccountDto update(@PathVariable UUID id, @RequestParam long version, @Valid @RequestBody AccountRequest request) {
        return settings.update(id, request, version);
    }

    @GetMapping("/settings")
    @Operation(summary = "Journaux, comptes auxiliaires, format du CSV")
    public SettingsDto settings() {
        return settings.settings();
    }

    @PutMapping("/settings")
    @Operation(summary = "Modifier les journaux, préfixes et le format du CSV")
    public SettingsDto updateSettings(@Valid @RequestBody SettingsDto request) {
        return settings.updateSettings(request);
    }
}
