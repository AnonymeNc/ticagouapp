package nc.ticagou.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.contract.ContractDtos.AmendmentRequest;
import nc.ticagou.contract.ContractDtos.ContractDraft;
import nc.ticagou.contract.ContractDtos.ContractDto;
import nc.ticagou.contract.ContractDtos.CreateContractRequest;
import nc.ticagou.contract.ContractDtos.Created;
import nc.ticagou.contract.ContractDtos.QuoteDto;
import nc.ticagou.contract.ContractDtos.QuoteRequest;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;

/**
 * Contrats d'accueil et avenants. Droits (ligne « Inscriptions et contrats ») : la direction modifie,
 * la comptabilité consulte ; l'éducatrice et la cuisine n'ont pas accès.
 */
@RestController
@RequestMapping("/api/v1/contracts")
@Tag(name = "Contrats", description = "Contrat d'accueil, forfait mensuel, avenants, PDF")
@RequiresModule(LicenseModule.INSCRIPTIONS)
public class ContractController {

    static final String READERS = "hasAnyRole('DIRECTION', 'COMPTABILITE')";
    static final String EDITORS = "hasRole('DIRECTION')";

    private final ContractService contracts;
    private final ContractPdfService pdfs;

    public ContractController(ContractService contracts, ContractPdfService pdfs) {
        this.contracts = contracts;
        this.pdfs = pdfs;
    }

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "Contrats d'un enfant, du plus récent au plus ancien")
    public List<ContractDto> forChild(@RequestParam UUID childId) {
        return contracts.forChild(childId);
    }

    @PostMapping("/quote")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Calculer le forfait et le coût de l'adaptation sans rien enregistrer")
    public QuoteDto quote(@Valid @RequestBody QuoteRequest request) {
        return contracts.quote(request);
    }

    @PostMapping
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un contrat en brouillon (depuis une demande, la fiche enfant est créée)")
    public Created create(@Valid @RequestBody CreateContractRequest request) {
        return new Created(contracts.create(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    @Operation(summary = "Contrat, ses versions et la version en vigueur")
    public ContractDto detail(@PathVariable UUID id) {
        return contracts.detail(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Modifier le contrat tant qu'il n'est pas signé")
    public void updateDraft(@PathVariable UUID id, @Valid @RequestBody ContractDraft draft) {
        contracts.updateDraft(id, draft);
    }

    @PostMapping("/{id}/versions")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un avenant en brouillon")
    public int amend(@PathVariable UUID id, @Valid @RequestBody AmendmentRequest request) {
        return contracts.amend(id, request);
    }

    @PutMapping("/{id}/versions/{number}")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Modifier un avenant en brouillon")
    public void updateAmendment(@PathVariable UUID id, @PathVariable int number,
                                @Valid @RequestBody AmendmentRequest request) {
        contracts.updateAmendment(id, number, request);
    }

    @DeleteMapping("/{id}/versions/{number}")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un brouillon (version 0 : le contrat entier)")
    public void deleteDraft(@PathVariable UUID id, @PathVariable int number) {
        contracts.deleteDraft(id, number);
    }

    @PostMapping(path = "/{id}/versions/{number}/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Enregistrer la signature papier, avec l'exemplaire signé numérisé si disponible")
    public void sign(@PathVariable UUID id, @PathVariable int number,
                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate signedOn,
                     @RequestParam(required = false) MultipartFile file) throws IOException {
        contracts.sign(id, number, signedOn, file == null ? null : file.getBytes(),
                file == null ? null : file.getOriginalFilename());
    }

    @GetMapping("/{id}/versions/{number}/pdf")
    @PreAuthorize(READERS)
    @Operation(summary = "PDF du contrat ou de l'avenant à imprimer")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, @PathVariable int number) {
        var pdf = pdfs.render(id, number);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(pdf.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(pdf.content());
    }
}
