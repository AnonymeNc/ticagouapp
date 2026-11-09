package nc.ticagou.portal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Format de la synchronisation avec le portail parents (ADR 0010, exemples dans {@code parents-portal/contract/}).
 * Les contenus envoyés sont la liste blanche de ce qui peut sortir : rien d'autre n'est sérialisé. Les fichiers
 * (photos, PDF) sont en Base64.
 */
public final class SyncDtos {

    private SyncDtos() {
    }

    // --- Activation ---------------------------------------------------------------------------

    public record ActivateRequest(String activationCode, String publicKey, String structureName) {
    }

    public record ActivateResponse(UUID structureId) {
    }

    // --- Envoi --------------------------------------------------------------------------------

    public record BatchItem(long seq, PortalItemType type, UUID id, boolean deleted, Object payload) {
    }

    public record Batch(List<BatchItem> items) {
    }

    public record BatchResponse(long acceptedUpTo) {
    }

    /**
     * {@code mealAbsenceCutoff} : heure avant laquelle une absence signalée évite la facturation du repas ; vide quand
     * la structure ne le pratique pas, et le portail n'en parle alors pas aux parents. {@code accessUntil} : dernier
     * jour d'accès des parents, celui où la crèche peut encore saisir (fin d'abonnement plus la grâce, ou fin
     * d'évaluation).
     */
    public record StructurePayload(String name, String phone, String paymentInfo, String mealAbsenceCutoff,
                                   LocalDate accessUntil) {
    }

    public record GuardianPayload(UUID familyId, String firstName, String lastName, String email) {
    }

    public record SharedHealth(String label, String text) {
    }

    public record ChildPayload(UUID familyId, String firstName, String lastInitial, String section, byte[] photo,
                               String photoType, List<SharedHealth> sharedHealth) {
    }

    public record AttendancePayload(UUID familyId, UUID childId, LocalDate day, Instant arrivedAt, Instant departedAt) {
    }

    public record EntryPayload(List<UUID> familyIds, List<UUID> childIds, LocalDate day, Instant at, String kind,
                               String text, Map<String, String> details, byte[] photo, String photoType) {
    }

    /** Ligne de facture telle qu'imprimée pour la famille (libellé, montant XPF, aides en négatif). */
    public record InvoiceLinePayload(String label, long amount) {
    }

    public record InvoicePayload(UUID familyId, String number, String kind, YearMonth month, LocalDate issuedOn,
                                 LocalDate dueOn, long total, long remaining, List<InvoiceLinePayload> lines, byte[] pdf) {
    }

    public record DocumentPayload(UUID familyId, UUID childId, String kind, String title, byte[] pdf, String sha256,
                                  String status) {
    }

    public record MessagePayload(UUID familyId, String author, String body, Instant sentAt) {
    }

    /** Réponse de la crèche à une demande d'accueil : remplace la demande du parent sur le portail (même identifiant). */
    public record BookingPayload(UUID familyId, UUID childId, LocalDate day, java.time.LocalTime arrivesAt,
                                 java.time.LocalTime leavesAt, String status, String answer) {
    }

    /**
     * Actualité de la crèche, pour les familles visées (toute la crèche ou une section). {@code expiresOn} : dernier
     * jour d'affichage, vide pour la durée de conservation du portail. Pièce jointe facultative (PDF ou image).
     */
    public record NewsPayload(List<UUID> familyIds, String title, String body, String author, Instant publishedAt,
                              LocalDate expiresOn, byte[] file, String fileType, String fileName) {
    }

    // --- Remontée -----------------------------------------------------------------------------

    public enum InboxType { ABSENCE, MESSAGE, SIGNATURE, BOOKING }

    public record InboxItem(long seq, InboxType type, UUID id, UUID familyId, JsonNode payload) {
    }

    public record Inbox(List<InboxItem> items) {
    }

    public record Ack(long upTo) {
    }

    public record AbsenceIn(UUID childId, UUID guardianId, LocalDate fromDay, LocalDate toDay, String reason,
                            Instant reportedAt) {
    }

    public record MessageIn(UUID guardianId, String body, Instant sentAt) {
    }

    /** Demande d'accueil occasionnel ({@code kind} RESERVATION) ou retard annoncé (RETARD, {@code leavesAt} : heure d'arrivée annoncée du parent). */
    public record BookingIn(UUID childId, UUID guardianId, String kind, LocalDate day, java.time.LocalTime arrivesAt,
                            java.time.LocalTime leavesAt, String note, Instant requestedAt) {
    }

    /** {@code decision} : ACCEPTE ou REFUSE (une autorisation peut être refusée ; un contrat se signe ou non). */
    public record SignatureIn(UUID documentId, UUID guardianId, String signerName, String decision, Instant signedAt,
                              String ip, String sha256) {

        public boolean refused() {
            return "REFUSE".equals(decision);
        }
    }
}
