package nc.ticagou.funder;

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
import nc.ticagou.funder.TariffDtos.DuplicateRequest;
import nc.ticagou.funder.TariffDtos.TariffScheduleDto;
import nc.ticagou.funder.TariffDtos.TariffScheduleRequest;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;

/** Barèmes : direction et comptabilité (maquette Droits par rôle, ligne Paramètres « Barème et financeurs »). */
@RestController
@RequestMapping("/api/v1/tariff-schedules")
@Tag(name = "Barèmes", description = "Tarif horaire par tranche, datés")
@PreAuthorize("hasAnyRole('DIRECTION', 'COMPTABILITE')")
@RequiresModule(LicenseModule.INSCRIPTIONS)
public class TariffController {

    private final TariffService tariffs;

    public TariffController(TariffService tariffs) {
        this.tariffs = tariffs;
    }

    @GetMapping
    @Operation(summary = "Barèmes de la structure, du plus récent au plus ancien")
    public List<TariffScheduleDto> list() {
        return tariffs.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un barème")
    public TariffScheduleDto create(@Valid @RequestBody TariffScheduleRequest request) {
        return tariffs.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier un barème (les contrats signés gardent leur tarif)")
    public TariffScheduleDto update(@PathVariable UUID id, @Valid @RequestBody TariffScheduleRequest request) {
        return tariffs.update(id, request);
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Dupliquer pour une nouvelle période ; le barème copié se termine la veille")
    public TariffScheduleDto duplicate(@PathVariable UUID id, @Valid @RequestBody DuplicateRequest request) {
        return tariffs.duplicate(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un barème inutilisé")
    public void delete(@PathVariable UUID id) {
        tariffs.delete(id);
    }
}
