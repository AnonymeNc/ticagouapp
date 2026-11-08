package nc.ticagou.portal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Portail parents d'une structure (ADR 0010). {@code DESACTIVE} : rien ne sort ; {@code PURGE_EN_COURS} : seul
 * l'ordre d'effacement est envoyé, jusqu'à son acquittement. La clé de signature est dans {@code server_secret}.
 */
@Entity
@Table(name = "portal_settings")
public class PortalSettings {

    public enum State { DESACTIVE, ACTIF, PURGE_EN_COURS }

    @Id
    @Column(name = "structure_id")
    private UUID structureId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state = State.DESACTIVE;

    @Column(name = "portal_url")
    private String portalUrl;

    @Column(name = "portal_structure_id")
    private UUID portalStructureId;

    @Column(name = "share_entries", nullable = false)
    private boolean shareEntries = true;

    @Column(name = "share_photos", nullable = false)
    private boolean sharePhotos = true;

    @Column(name = "share_invoices", nullable = false)
    private boolean shareInvoices = true;

    @Column(name = "share_documents", nullable = false)
    private boolean shareDocuments = true;

    @Column(name = "share_messages", nullable = false)
    private boolean shareMessages = true;

    @Column(name = "accept_absences", nullable = false)
    private boolean acceptAbsences = true;

    /** Heure limite (HH:mm) pour signaler une absence sans repas facturé ; vide si la structure ne le pratique pas. */
    @Column(name = "meal_absence_cutoff")
    private String mealAbsenceCutoff;

    @Column(name = "payment_info")
    private String paymentInfo;

    @Column(name = "inbox_cursor", nullable = false)
    private long inboxCursor;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(nullable = false)
    private int failures;

    /** Dernier jour d'accès des parents mis dans la file : un changement d'abonnement le renvoie au portail. */
    @Column(name = "access_until_sent")
    private LocalDate accessUntilSent;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PortalSettings() {
    }

    public PortalSettings(UUID structureId, Instant now) {
        this.structureId = structureId;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return state == State.ACTIF;
    }

    public void activate(String url, UUID portalStructureId, Instant now) {
        this.state = State.ACTIF;
        this.portalUrl = url;
        this.portalStructureId = portalStructureId;
        this.inboxCursor = 0;
        this.failures = 0;
        this.lastError = null;
        this.activatedAt = now;
        this.updatedAt = now;
    }

    public void share(boolean entries, boolean photos, boolean invoices, boolean documents, boolean messages,
                      boolean absences, String paymentInfo, String mealAbsenceCutoff, Instant now) {
        this.mealAbsenceCutoff = mealAbsenceCutoff;
        this.shareEntries = entries;
        this.sharePhotos = photos;
        this.shareInvoices = invoices;
        this.shareDocuments = documents;
        this.shareMessages = messages;
        this.acceptAbsences = absences;
        this.paymentInfo = paymentInfo;
        this.updatedAt = now;
    }

    public void startPurge(Instant now) {
        this.state = State.PURGE_EN_COURS;
        this.updatedAt = now;
    }

    public void disabled(Instant now) {
        this.state = State.DESACTIVE;
        this.portalStructureId = null;
        this.accessUntilSent = null;
        this.inboxCursor = 0;
        this.failures = 0;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void succeeded(Instant now) {
        this.lastAttemptAt = now;
        this.lastSuccessAt = now;
        this.lastError = null;
        this.failures = 0;
    }

    public void failed(String error, Instant now) {
        this.lastAttemptAt = now;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        this.failures++;
    }

    public void accessUntilSent(LocalDate day) {
        this.accessUntilSent = day;
    }

    public LocalDate getAccessUntilSent() {
        return accessUntilSent;
    }

    public void inboxCursor(long cursor) {
        this.inboxCursor = cursor;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public State getState() {
        return state;
    }

    public String getPortalUrl() {
        return portalUrl;
    }

    public UUID getPortalStructureId() {
        return portalStructureId;
    }

    public boolean isShareEntries() {
        return shareEntries;
    }

    public boolean isSharePhotos() {
        return sharePhotos;
    }

    public boolean isShareInvoices() {
        return shareInvoices;
    }

    public boolean isShareDocuments() {
        return shareDocuments;
    }

    public boolean isShareMessages() {
        return shareMessages;
    }

    public boolean isAcceptAbsences() {
        return acceptAbsences;
    }

    public String getMealAbsenceCutoff() {
        return mealAbsenceCutoff;
    }

    public String getPaymentInfo() {
        return paymentInfo;
    }

    public long getInboxCursor() {
        return inboxCursor;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public int getFailures() {
        return failures;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }
}
