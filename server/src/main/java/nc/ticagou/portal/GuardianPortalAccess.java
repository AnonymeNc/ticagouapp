package nc.ticagou.portal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Responsable invité au portail par la direction : seuls ceux-là, et leur famille, sont envoyés. */
@Entity
@Table(name = "guardian_portal_access")
public class GuardianPortalAccess {

    @Id
    @Column(name = "guardian_id")
    private UUID guardianId;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(nullable = false)
    private String email;

    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt;

    @Column(name = "invited_by", nullable = false)
    private String invitedBy;

    protected GuardianPortalAccess() {
    }

    public GuardianPortalAccess(UUID guardianId, UUID structureId, UUID familyId, String email, Instant invitedAt,
                                String invitedBy) {
        this.guardianId = guardianId;
        this.structureId = structureId;
        this.familyId = familyId;
        this.email = email;
        this.invitedAt = invitedAt;
        this.invitedBy = invitedBy;
    }

    public void email(String email) {
        this.email = email;
    }

    public UUID getGuardianId() {
        return guardianId;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getEmail() {
        return email;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public String getInvitedBy() {
        return invitedBy;
    }
}
