package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import nc.ticagou.billing.BillingDtos.InvoiceSummary;
import nc.ticagou.billing.Payment.Method;

/** Encaissements, lettrage et relances. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * Paiement d'une famille ({@code invoiceId} : lettré d'abord sur cette facture, puis sur ses autres impayés,
     * du plus ancien au plus récent ; {@code familyId} seul : réparti sur ses impayés) ou d'un financeur
     * ({@code funderRequestId}).
     */
    public record PaymentRequest(
            UUID invoiceId,
            UUID familyId,
            UUID funderRequestId,
            @NotNull LocalDate paidOn,
            @Positive long amount,
            @NotNull Method method,
            @Size(max = 80) String reference,
            @Size(max = 80) String bank) {

        @AssertTrue(message = "Choisissez une facture, une famille ou une demande au financeur")
        @JsonIgnore
        public boolean isTargetGiven() {
            return (invoiceId != null || familyId != null) != (funderRequestId != null);
        }

        @AssertTrue(message = "Indiquez le numéro du chèque")
        @JsonIgnore
        public boolean isChequeNumberGiven() {
            return method != Method.CHEQUE || (reference != null && !reference.isBlank());
        }
    }

    public record AllocationLine(UUID invoiceId, UUID funderRequestId, String number, long amount) {
    }

    public record PaymentDto(UUID id, String payerName, UUID familyId, UUID funderId, LocalDate paidOn, long amount,
                             Method method, String reference, String bank, String receiptNumber, String recordedBy,
                             boolean cancelled, String cancelReason, List<AllocationLine> allocations) {
    }

    public record CancelPaymentRequest(@NotBlank @Size(max = 300) String reason) {
    }

    /** Chiffres clés de la maquette « Encaissements et relances ». */
    public record CollectionsOverview(long collectedThisMonth, int collectedCount, long upcoming, int upcomingCount,
                                      LocalDate nextDueOn, long overdue, int overdueCount, int overdueOver30,
                                      List<InvoiceSummary> unpaid, List<PaymentDto> recentPayments) {
    }

    public record ReminderDto(UUID id, UUID invoiceId, String invoiceNumber, int level, LocalDate createdOn,
                              LocalDate sentOn, String author) {
    }

    public record MarkReminderSentRequest(@NotNull LocalDate sentOn) {
    }
}
