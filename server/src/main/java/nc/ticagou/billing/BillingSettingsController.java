package nc.ticagou.billing;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.billing.BillingSettingsDtos.AbsenceReasonDto;
import nc.ticagou.billing.BillingSettingsDtos.AbsenceReasonRequest;
import nc.ticagou.billing.BillingSettingsDtos.BillingRuleDto;
import nc.ticagou.billing.BillingSettingsDtos.BillingRuleRequest;
import nc.ticagou.billing.BillingSettingsDtos.TgcRateDto;
import nc.ticagou.billing.BillingSettingsDtos.TgcRateRequest;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;

/** Paramètres › Barème et facturation : direction et comptabilité (motifs d'absence lisibles par les éducatrices). */
@RestController
@RequiresModule(LicenseModule.FACTURATION)
@RequestMapping("/api/v1/billing")
@Tag(name = "Paramètres de facturation", description = "Règles, motifs d'absence et TGC, datés")
@PreAuthorize(BillingSettingsController.MANAGERS)
public class BillingSettingsController {

    /** Maquette « Facturation et paiements » : direction et comptabilité ; éducatrices et cuisine n'y ont pas accès. */
    static final String MANAGERS = "hasAnyRole('DIRECTION', 'COMPTABILITE')";

    private final BillingSettingsService settings;

    public BillingSettingsController(BillingSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/rules")
    @Operation(summary = "Règles de facturation, de la plus récente à la plus ancienne")
    public List<BillingRuleDto> rules() {
        return settings.listRules();
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter une règle pour une nouvelle période")
    public BillingRuleDto createRule(@Valid @RequestBody BillingRuleRequest request) {
        return settings.createRule(request);
    }

    @PutMapping("/rules/{id}")
    @Operation(summary = "Modifier une règle (les factures émises ne changent pas)")
    public BillingRuleDto updateRule(@PathVariable UUID id, @Valid @RequestBody BillingRuleRequest request) {
        return settings.updateRule(id, request);
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une règle (sauf la dernière)")
    public void deleteRule(@PathVariable UUID id) {
        settings.deleteRule(id);
    }

    @GetMapping("/absence-reasons")
    @PreAuthorize("hasAnyRole('DIRECTION', 'COMPTABILITE', 'EDUCATRICE')")
    @Operation(summary = "Motifs d'absence")
    public List<AbsenceReasonDto> reasons() {
        return settings.listReasons();
    }

    @PostMapping("/absence-reasons")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter un motif d'absence")
    public AbsenceReasonDto createReason(@Valid @RequestBody AbsenceReasonRequest request) {
        return settings.createReason(request);
    }

    @PutMapping("/absence-reasons/{id}")
    @Operation(summary = "Modifier un motif d'absence")
    public AbsenceReasonDto updateReason(@PathVariable UUID id, @Valid @RequestBody AbsenceReasonRequest request) {
        return settings.updateReason(id, request);
    }

    @DeleteMapping("/absence-reasons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un motif jamais utilisé")
    public void deleteReason(@PathVariable UUID id) {
        settings.deleteReason(id);
    }

    @GetMapping("/tgc-rates")
    @Operation(summary = "Taux de TGC par prestation")
    public List<TgcRateDto> tgcRates() {
        return settings.listTgcRates();
    }

    @PostMapping("/tgc-rates")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter un taux de TGC pour une prestation et une période")
    public TgcRateDto createTgcRate(@Valid @RequestBody TgcRateRequest request) {
        return settings.createTgcRate(request);
    }

    @PutMapping("/tgc-rates/{id}")
    @Operation(summary = "Modifier un taux de TGC")
    public TgcRateDto updateTgcRate(@PathVariable UUID id, @Valid @RequestBody TgcRateRequest request) {
        return settings.updateTgcRate(id, request);
    }

    @DeleteMapping("/tgc-rates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un taux de TGC")
    public void deleteTgcRate(@PathVariable UUID id) {
        settings.deleteTgcRate(id);
    }
}
