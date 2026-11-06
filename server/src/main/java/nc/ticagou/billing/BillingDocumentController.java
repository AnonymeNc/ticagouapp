package nc.ticagou.billing;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nc.ticagou.billing.BillingDocuments.Document;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;

/** PDF A4 des pièces de facturation et export Excel des demandes aux financeurs. */
@RestController
@RequiresModule(LicenseModule.FACTURATION)
@RequestMapping("/api/v1/billing")
@Tag(name = "Pièces de facturation", description = "Factures, avoirs, demandes, relances, reçus, attestations")
@PreAuthorize(BillingSettingsController.MANAGERS)
public class BillingDocumentController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BillingDocuments documents;

    public BillingDocumentController(BillingDocuments documents) {
        this.documents = documents;
    }

    @GetMapping("/invoices/{id}/pdf")
    @Operation(summary = "Facture en PDF")
    public ResponseEntity<byte[]> invoice(@PathVariable UUID id) {
        return inline(documents.invoice(id));
    }

    @GetMapping("/credit-notes/{id}/pdf")
    @Operation(summary = "Avoir en PDF")
    public ResponseEntity<byte[]> creditNote(@PathVariable UUID id) {
        return inline(documents.creditNote(id));
    }

    @GetMapping("/funder-requests/{id}/pdf")
    @Operation(summary = "Demande au financeur et relevés de présence (justificatifs) en PDF")
    public ResponseEntity<byte[]> funderRequest(@PathVariable UUID id) {
        return inline(documents.funderRequest(id));
    }

    @GetMapping("/funder-requests/export")
    @Operation(summary = "Demandes du mois au format Excel")
    public ResponseEntity<byte[]> funderRequestsXlsx(@RequestParam YearMonth month) {
        var document = documents.funderRequestsXlsx(month);
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(document.content());
    }

    @GetMapping("/reminders/{id}/pdf")
    @Operation(summary = "Lettre de relance en PDF")
    public ResponseEntity<byte[]> reminder(@PathVariable UUID id) {
        return inline(documents.reminder(id));
    }

    @GetMapping("/payments/{id}/receipt")
    @Operation(summary = "Reçu numéroté d'un paiement en espèces")
    public ResponseEntity<byte[]> receipt(@PathVariable UUID id) {
        return inline(documents.receipt(id));
    }

    @GetMapping("/families/{familyId}/attestation")
    @Operation(summary = "Attestation annuelle de frais de garde d'une famille")
    public ResponseEntity<byte[]> attestation(@PathVariable UUID familyId, @RequestParam int year) {
        return inline(documents.attestation(familyId, year));
    }

    private static ResponseEntity<byte[]> inline(Document document) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(document.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(document.content());
    }
}
