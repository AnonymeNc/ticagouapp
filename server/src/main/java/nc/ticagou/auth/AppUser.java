package nc.ticagou.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Compte d'un membre du personnel. Mot de passe pour le PC du bureau, PIN pour la tablette ;
 * chacun a son propre compteur d'échecs et son blocage temporaire.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private String login;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "failed_pin_attempts", nullable = false)
    private int failedPinAttempts;

    @Column(name = "pin_locked_until")
    private Instant pinLockedUntil;

    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_enabled_at")
    private Instant totpEnabledAt;

    @Column(name = "totp_last_step")
    private Long totpLastStep;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Fonction affichée sur la tablette et le tableau de bord (« Auxiliaire de puériculture »). */
    @Column(name = "job_title")
    private String jobTitle;

    /** Section où la personne pointe par défaut ; vide = choisie à chaque arrivée. */
    @Column(name = "home_section_id")
    private UUID homeSectionId;

    /** Compte dans le taux d'encadrement (la direction et la cuisine, en général, non). */
    @Column(name = "counts_in_ratio", nullable = false)
    private boolean countsInRatio;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AppUser() {
    }

    public AppUser(UUID structureId, String firstName, String lastName, Role role) {
        this.structureId = structureId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.role = role;
        this.countsInRatio = role == Role.EDUCATRICE;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String displayName() {
        return firstName + " " + lastName;
    }

    /** « Sandrine W. » : affichage sur la tablette, sans le nom complet. */
    public String shortName() {
        return firstName + " " + lastName.charAt(0) + ".";
    }

    // --- Mot de passe ---------------------------------------------------------

    public boolean isPasswordLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public void passwordFailed(Instant now, int maxAttempts, Duration lockDuration) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockDuration);
            failedLoginAttempts = 0;
        }
    }

    public void passwordSucceeded(Instant now) {
        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public boolean isTotpEnabled() {
        return totpEnabledAt != null;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public Long getTotpLastStep() {
        return totpLastStep;
    }

    /** Direction et comptabilité, connectées par mot de passe : la tablette (PIN) n'est pas concernée. */
    public boolean needsMfaEnrollment(boolean required, AuthMethod method) {
        return required && method == AuthMethod.PASSWORD && !isTotpEnabled()
                && (role == Role.DIRECTION || role == Role.COMPTABILITE);
    }

    public void startTotp(String secret) {
        this.totpSecret = secret;
    }

    public void enableTotp(Instant now, long step) {
        this.totpEnabledAt = now;
        this.totpLastStep = step;
    }

    public void totpUsed(long step) {
        this.totpLastStep = step;
    }

    public void resetTotp() {
        this.totpSecret = null;
        this.totpEnabledAt = null;
        this.totpLastStep = null;
    }

    public void changePassword(String login, String passwordHash) {
        this.login = login;
        this.passwordHash = passwordHash;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    // --- PIN --------------------------------------------------------------------

    public boolean isPinLocked(Instant now) {
        return pinLockedUntil != null && now.isBefore(pinLockedUntil);
    }

    public void pinFailed(Instant now, int maxAttempts, Duration lockDuration) {
        failedPinAttempts++;
        if (failedPinAttempts >= maxAttempts) {
            pinLockedUntil = now.plus(lockDuration);
            failedPinAttempts = 0;
        }
    }

    public void pinSucceeded(Instant now) {
        failedPinAttempts = 0;
        pinLockedUntil = null;
        lastLoginAt = now;
    }

    public void changePin(String pinHash) {
        this.pinHash = pinHash;
        this.failedPinAttempts = 0;
        this.pinLockedUntil = null;
    }

    // --- Profil -------------------------------------------------------------------

    public void updateProfile(String firstName, String lastName, Role role, String login, boolean active) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.role = role;
        this.login = login;
        this.active = active;
    }

    public void updateStaffing(String jobTitle, UUID homeSectionId, boolean countsInRatio) {
        this.jobTitle = jobTitle;
        this.homeSectionId = homeSectionId;
        this.countsInRatio = countsInRatio;
    }

    public String initials() {
        return ("" + firstName.charAt(0) + lastName.charAt(0)).toUpperCase();
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public Role getRole() {
        return role;
    }

    public String getLogin() {
        return login;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPinHash() {
        return pinHash;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getPinLockedUntil() {
        return pinLockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public UUID getHomeSectionId() {
        return homeSectionId;
    }

    public boolean isCountsInRatio() {
        return countsInRatio;
    }
}
