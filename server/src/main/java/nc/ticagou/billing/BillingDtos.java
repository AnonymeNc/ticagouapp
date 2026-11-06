package nc.ticagou.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import nc.ticagou.billing.InvoiceCalculator.Kind;

/** Facturer le mois, factures, avoirs, demandes aux financeurs. */
public final class BillingDtos {

    private BillingDtos() {
    }

    // ---------------------------------------------------------------- Facturer le mois

    public enum FamilyStatus { PRETE, ANOMALIE, FACTUREE, RIEN }

    public record FunderColumn(UUID id, String name) {
    }

    public record AnomalyDto(UUID familyId, String familyLabel, UUID childId, String childName, String type,
                             String message, List<LocalDate> days) {
    }

    /**
     * Une ligne par famille. Montants hors TGC sauf {@code careTotal} (frais de garde TGC comprise) et
     * {@code familyTotal} (reste à charge arrondi).
     */
    public record FamilyRow(UUID familyId, String familyLabel, String payerName, List<String> children, long forfait,
                            long extraHours, long overtime, long absences, long other, long tgc, long careTotal,
                            Map<UUID, Long> aids, long aidTotal, long familyTotal, FamilyStatus status,
                            int anomalyCount, UUID invoiceId, String invoiceNumber) {
    }

    public record PreviewTotals(long careTotal, long familyTotal, long aidTotal, Map<UUID, Long> aidByFunder,
                                int families, int ready, int withAnomalies, int invoiced) {
    }

    public record MonthPreview(YearMonth month, boolean generationAllowed, List<FunderColumn> funders,
                               List<FamilyRow> families, List<AnomalyDto> anomalies, PreviewTotals totals) {
    }

    public record LineDto(UUID childId, String childName, Kind kind, String label, String detail, String quantity,
                          Long unitPrice, long amount, int tgcRate, long tgcAmount, long total, UUID funderId) {
    }

    public record FamilyPreview(FamilyRow row, String payerAddress, String contractText, LocalDate dueOn,
                                List<LineDto> lines, long careTotal, long tgcTotal, long aidTotal, long rounding,
                                long familyTotal, List<String> notes, List<AnomalyDto> anomalies) {
    }

    /** {@code familyIds} vide ou absent : toutes les familles prêtes. */
    public record GenerateRequest(List<UUID> familyIds) {
    }

    public record FunderRequestSummary(UUID id, String number, String funderName, long total) {
    }

    public record GenerationResult(YearMonth month, int invoices, String firstNumber, String lastNumber,
                                   long familyTotal, long aidTotal, List<String> skipped,
                                   List<FunderRequestSummary> funderRequests) {
    }

    // ---------------------------------------------------------------- factures

    public enum InvoiceStatus { EMISE, PARTIELLE, PAYEE, EN_RETARD, ANNULEE }

    public record InvoiceSummary(UUID id, String number, UUID familyId, String familyLabel, String payerName,
                                 YearMonth month, LocalDate issuedOn, LocalDate dueOn, long familyTotal,
                                 long aidTotal, long credited, long paid, long remaining, InvoiceStatus status,
                                 LocalDate sentOn, int reminderLevel, long daysLate) {
    }

    public record EventDto(String kind, String label, String author, Instant at) {
    }

    public record AllocationDto(UUID paymentId, LocalDate paidOn, String method, String reference, long amount,
                                boolean cancelled) {
    }

    public record CreditNoteLineDto(String label, long amount) {
    }

    public record CreditNoteDto(UUID id, String number, UUID invoiceId, String invoiceNumber, LocalDate issuedOn,
                                String reason, boolean fullCancel, long familyTotal, long aidTotal,
                                List<CreditNoteLineDto> lines, String issuedBy) {
    }

    public record InvoiceDto(InvoiceSummary summary, String payerAddress, long careTotal, long tgcTotal,
                             long rounding, String contractText, String issuedBy, List<LineDto> lines,
                             List<CreditNoteDto> creditNotes, List<AllocationDto> payments, List<EventDto> events) {
    }

    public record MarkSentRequest(@NotNull LocalDate sentOn, @Size(max = 80) String channel) {
    }

    public record CreditLineRequest(@NotBlank @Size(max = 200) String label, @Positive long amount) {
    }

    /** Avoir total (toute la facture, la famille peut être refacturée) ou partiel (lignes saisies). */
    public record CreditNoteRequest(@NotBlank @Size(max = 300) String reason, boolean full,
                                    @Size(max = 10) List<@Valid CreditLineRequest> lines) {

        @AssertTrue(message = "Indiquez au moins une ligne pour un avoir partiel")
        @JsonIgnore
        public boolean isLinesGiven() {
            return full || (lines != null && !lines.isEmpty());
        }
    }

    // ---------------------------------------------------------------- demandes aux financeurs

    public enum RequestStatus { A_ENVOYER, ENVOYEE, PARTIELLE, REGLEE }

    public record FunderRequestLineDto(UUID childId, String childName, String beneficiary, UUID invoiceId,
                                       String invoiceNumber, int attendanceDays, long amount) {
    }

    public record FunderRequestDto(UUID id, String number, UUID funderId, String funderName, String conventionRef,
                                   YearMonth month, long total, long received, long remaining, LocalDate sentOn,
                                   String sentBy, RequestStatus status, List<FunderRequestLineDto> lines,
                                   long version) {
    }

    public record MarkRequestSentRequest(@NotNull LocalDate sentOn) {
    }
}
