package nc.ticagou.portal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nc.ticagou.child.Consent;

public final class PortalDtos {

    private PortalDtos() {
    }

    // --- Réglages -----------------------------------------------------------------------------

    public record Shares(boolean entries, boolean photos, boolean invoices, boolean documents, boolean messages,
                        boolean absences, @Size(max = 300) String paymentInfo,
                        @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]", message = "Heure invalide (HH:MM)") String mealAbsenceCutoff) {
    }

    public record PortalStatus(boolean moduleIncluded, PortalSettings.State state, String portalUrl,
                               Instant activatedAt, Shares shares, Instant lastSuccessAt, Instant lastAttemptAt,
                               String lastError, long pending, int invitedGuardians) {
    }

    public record ActivateRequest(
            @NotBlank @Size(max = 255) @Pattern(regexp = "https?://\\S+", message = "Adresse du portail invalide")
            String portalUrl,
            @NotBlank @Size(max = 40) String activationCode) {
    }

    public record DeactivateRequest(@NotBlank String confirmation) {
    }

    public record SyncNow(int sent, int received, String error, PortalStatus status) {
    }

    // --- Accès des familles -------------------------------------------------------------------

    public record GuardianAccess(UUID guardianId, String firstName, String lastName, String email, boolean invited,
                                 Instant invitedAt, String invitedBy) {
    }

    public record FamilyAccess(UUID familyId, boolean portalActive, List<GuardianAccess> guardians) {
    }

    // --- Santé partagée -----------------------------------------------------------------------

    public record HealthShareRequest(@NotBlank @Size(max = 60) String label, @NotBlank @Size(max = 300) String text) {
    }

    public record HealthShareDto(UUID id, String label, String text, String sharedBy, Instant sharedAt) {
    }

    // --- Documents à signer -------------------------------------------------------------------

    public record ProposeContract(@NotNull UUID contractId, int versionNumber) {
    }

    public record ProposeConsent(@NotNull UUID childId, @NotNull Consent.Type type) {
    }

    public record PortalDocumentDto(UUID id, UUID familyId, UUID childId, String childName, PortalDocument.Kind kind,
                                    String title, PortalDocument.Status status, String proposedBy, Instant proposedAt,
                                    String signerName, boolean refused, Instant signedAt, String signerIp, String sha256,
                                    String recordedBy, Instant recordedAt) {
    }

    // --- Messages -----------------------------------------------------------------------------

    public record MessageRequest(@NotBlank @Size(max = 2000) String body) {
    }

    public record MessageDto(UUID id, FamilyMessage.Direction direction, String authorName, String body, Instant sentAt,
                             Instant readAt) {
    }

    public record ThreadSummary(UUID familyId, String familyLabel, String lastBody, Instant lastAt, long unread,
                                boolean portalAccess) {
    }

    public record Thread(UUID familyId, String familyLabel, boolean portalAccess, List<MessageDto> messages) {
    }

    /** Message à toutes les familles invitées ({@code sectionId} vide) ou à celles d'une section. */
    public record BroadcastRequest(UUID sectionId, @NotBlank @Size(max = 2000) String body) {
    }

    public record BroadcastResult(int families) {
    }

    // --- Actualités ---------------------------------------------------------------------------

    /** {@code status} : PUBLIEE, EXPIREE ou RETIREE ; {@code families} : familles qui la voient (publiée seulement). */
    public record NewsDto(UUID id, UUID sectionId, String sectionName, String title, String body, String fileName,
                          String publishedBy, Instant publishedAt, LocalDate expiresOn, Instant withdrawnAt,
                          String status, int families) {
    }

    public record FileContent(byte[] content, String contentType, String fileName) {
    }

    // --- Absences signalées -------------------------------------------------------------------

    public record ReportedAbsenceDto(UUID id, UUID childId, String childName, String guardianName, LocalDate fromDay,
                                     LocalDate toDay, String reason, Instant reportedAt, ReportedAbsence.Status status) {
    }

    public record ConfirmAbsence(@NotNull UUID reasonId) {
    }
}
