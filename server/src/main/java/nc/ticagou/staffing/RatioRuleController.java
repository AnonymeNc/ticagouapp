package nc.ticagou.staffing;

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
import nc.ticagou.staffing.StaffingDtos.RatioRuleDto;
import nc.ticagou.staffing.StaffingDtos.RatioRuleRequest;
import nc.ticagou.staffing.StaffingDtos.SettingsDto;

/** Règles d'encadrement : direction (maquette Paramètres › Règles d'encadrement). */
@RestController
@RequestMapping("/api/v1/ratio-rules")
@Tag(name = "Encadrement", description = "Ratios par tranche d'âge, datés")
@PreAuthorize("hasRole('DIRECTION')")
public class RatioRuleController {

    private final RatioRuleService rules;

    public RatioRuleController(RatioRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    @Operation(summary = "Règles de la structure, avec leur statut à la date du jour")
    public List<RatioRuleDto> list() {
        return rules.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter une règle")
    public RatioRuleDto create(@Valid @RequestBody RatioRuleRequest request) {
        return rules.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier une règle")
    public RatioRuleDto update(@PathVariable UUID id, @Valid @RequestBody RatioRuleRequest request) {
        return rules.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une règle")
    public void delete(@PathVariable UUID id) {
        rules.delete(id);
    }

    @GetMapping("/settings")
    @Operation(summary = "Déclenchement des alertes")
    public SettingsDto settings() {
        return rules.settings();
    }

    @PutMapping("/settings")
    @Operation(summary = "Modifier le déclenchement des alertes")
    public SettingsDto updateSettings(@Valid @RequestBody SettingsDto request) {
        return rules.updateSettings(request);
    }
}
