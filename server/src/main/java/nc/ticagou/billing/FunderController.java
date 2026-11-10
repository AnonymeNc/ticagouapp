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
import nc.ticagou.billing.FunderDtos.ChildFundingDto;
import nc.ticagou.billing.FunderDtos.ChildFundingRequest;
import nc.ticagou.billing.FunderDtos.FunderDto;
import nc.ticagou.billing.FunderDtos.FunderRequest;

/** Financeurs et aides accordées par enfant : direction et comptabilité. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Financeurs", description = "Aides provinciales, organismes, employeurs conventionnés")
@PreAuthorize(BillingSettingsController.MANAGERS)
public class FunderController {

    private final FunderService funders;

    public FunderController(FunderService funders) {
        this.funders = funders;
    }

    @GetMapping("/billing/funders")
    @Operation(summary = "Financeurs, par priorité")
    public List<FunderDto> list() {
        return funders.list();
    }

    @PostMapping("/billing/funders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter un financeur")
    public FunderDto create(@Valid @RequestBody FunderRequest request) {
        return funders.create(request);
    }

    @PutMapping("/billing/funders/{id}")
    @Operation(summary = "Modifier un financeur (les factures émises ne changent pas)")
    public FunderDto update(@PathVariable UUID id, @Valid @RequestBody FunderRequest request) {
        return funders.update(id, request);
    }

    @DeleteMapping("/billing/funders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un financeur sans bénéficiaire")
    public void delete(@PathVariable UUID id) {
        funders.delete(id);
    }

    @GetMapping("/children/{childId}/fundings")
    @Operation(summary = "Aides accordées à un enfant")
    public List<ChildFundingDto> fundings(@PathVariable UUID childId) {
        return funders.listForChild(childId);
    }

    @PostMapping("/children/{childId}/fundings")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Accorder une aide à un enfant")
    public ChildFundingDto createFunding(@PathVariable UUID childId, @Valid @RequestBody ChildFundingRequest request) {
        return funders.create(childId, request);
    }

    @PutMapping("/children/{childId}/fundings/{id}")
    @Operation(summary = "Modifier une aide accordée")
    public ChildFundingDto updateFunding(@PathVariable UUID childId, @PathVariable UUID id,
                                         @Valid @RequestBody ChildFundingRequest request) {
        return funders.update(childId, id, request);
    }

    @DeleteMapping("/children/{childId}/fundings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer une aide accordée")
    public void deleteFunding(@PathVariable UUID childId, @PathVariable UUID id) {
        funders.delete(childId, id);
    }
}
