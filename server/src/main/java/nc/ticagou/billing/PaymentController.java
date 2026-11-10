package nc.ticagou.billing;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.billing.PaymentDtos.CancelPaymentRequest;
import nc.ticagou.billing.PaymentDtos.CollectionsOverview;
import nc.ticagou.billing.PaymentDtos.MarkReminderSentRequest;
import nc.ticagou.billing.PaymentDtos.PaymentDto;
import nc.ticagou.billing.PaymentDtos.PaymentRequest;
import nc.ticagou.billing.PaymentDtos.ReminderDto;

/** Encaissements, lettrage et relances : direction et comptabilité. */
@RestController
@RequestMapping("/api/v1/billing")
@Tag(name = "Encaissements", description = "Paiements, lettrage, impayés et relances")
@PreAuthorize(BillingSettingsController.MANAGERS)
public class PaymentController {

    private final PaymentService payments;
    private final ReminderService reminders;

    public PaymentController(PaymentService payments, ReminderService reminders) {
        this.payments = payments;
        this.reminders = reminders;
    }

    @GetMapping("/collections")
    @Operation(summary = "Encaissé du mois, à venir, impayés échus, derniers paiements")
    public CollectionsOverview collections() {
        return payments.collections();
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enregistrer un paiement et le lettrer")
    public PaymentDto record(@Valid @RequestBody PaymentRequest request) {
        return payments.record(request);
    }

    @GetMapping("/payments/{id}")
    @Operation(summary = "Détail d'un paiement et de son lettrage")
    public PaymentDto payment(@PathVariable UUID id) {
        return payments.get(id);
    }

    @PostMapping("/payments/{id}/cancel")
    @Operation(summary = "Annuler un paiement (motif obligatoire, tracé)")
    public PaymentDto cancel(@PathVariable UUID id, @Valid @RequestBody CancelPaymentRequest request) {
        return payments.cancel(id, request);
    }

    @GetMapping("/invoices/{id}/reminders")
    @Operation(summary = "Relances d'une facture")
    public List<ReminderDto> reminders(@PathVariable UUID id) {
        return reminders.list(id);
    }

    @PostMapping("/invoices/{id}/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Préparer la relance suivante d'une facture échue")
    public ReminderDto remind(@PathVariable UUID id) {
        return reminders.create(id);
    }

    @PostMapping("/reminders/{id}/sent")
    @Operation(summary = "Noter la relance comme envoyée")
    public ReminderDto markReminderSent(@PathVariable UUID id, @Valid @RequestBody MarkReminderSentRequest request) {
        return reminders.markSent(id, request);
    }
}
