package nc.ticagou.enrollment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Demande d'inscription, suivie de la première prise de contact à la signature du contrat ou au refus. */
@Entity
@Table(name = "enrollment_request")
public class EnrollmentRequest {

    public enum Stage {
        NOUVELLE("Nouvelle demande"),
        A_RECONTACTER("À recontacter"),
        VISITE_PREVUE("Visite prévue"),
        PLACE_PROPOSEE("Place proposée"),
        SIGNEE("Contrat signé"),
        REFUSEE("Refusée");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean isClosed() {
            return this == SIGNEE || this == REFUSEE;
        }
    }

    public enum CareType { REGULIER, OCCASIONNEL }

    /** Données saisies sur la demande (enfant, accueil souhaité, contact). */
    public record Details(String childFirstName, String childLastName, LocalDate birthDate, LocalDate expectedBirthDate,
                          UUID sectionId, LocalDate desiredStart, CareType careType, LocalTime arrivesAt,
                          LocalTime leavesAt, Collection<Integer> days, String guardianFirstName,
                          String guardianLastName, String relationship, String phone, String email, UUID familyId,
                          Collection<UUID> criteria) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "received_on", nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Column(name = "child_first_name", nullable = false)
    private String childFirstName;

    @Column(name = "child_last_name", nullable = false)
    private String childLastName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "expected_birth_date")
    private LocalDate expectedBirthDate;

    @Column(name = "section_id")
    private UUID sectionId;

    @Column(name = "desired_start", nullable = false)
    private LocalDate desiredStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "care_type", nullable = false)
    private CareType careType;

    @Column(name = "arrives_at")
    private LocalTime arrivesAt;

    @Column(name = "leaves_at")
    private LocalTime leavesAt;

    @ElementCollection
    @CollectionTable(name = "enrollment_request_day", joinColumns = @JoinColumn(name = "request_id"))
    @Column(name = "day_of_week", nullable = false)
    private Set<Integer> days = new TreeSet<>();

    @ElementCollection
    @CollectionTable(name = "enrollment_request_criterion", joinColumns = @JoinColumn(name = "request_id"))
    @Column(name = "criterion_id", nullable = false)
    private Set<UUID> criteria = new HashSet<>();

    @Column(name = "guardian_first_name", nullable = false)
    private String guardianFirstName;

    @Column(name = "guardian_last_name", nullable = false)
    private String guardianLastName;

    @Column(nullable = false)
    private String relationship;

    private String phone;

    private String email;

    @Column(name = "family_id")
    private UUID familyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Stage stage = Stage.NOUVELLE;

    @Column(name = "stage_changed_on", nullable = false)
    private LocalDate stageChangedOn;

    @Column(name = "follow_up_on")
    private LocalDate followUpOn;

    @Column(name = "visit_at")
    private LocalDateTime visitAt;

    @Column(name = "visit_done", nullable = false)
    private boolean visitDone;

    @Column(name = "offer_expires_on")
    private LocalDate offerExpiresOn;

    @Column(name = "refusal_reason")
    private String refusalReason;

    @Column(name = "child_id")
    private UUID childId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected EnrollmentRequest() {
    }

    public EnrollmentRequest(UUID structureId, LocalDate receivedOn) {
        this.structureId = structureId;
        this.receivedOn = receivedOn;
        this.stageChangedOn = receivedOn;
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

    public void update(Details d) {
        this.childFirstName = d.childFirstName();
        this.childLastName = d.childLastName();
        this.birthDate = d.birthDate();
        this.expectedBirthDate = d.birthDate() == null ? d.expectedBirthDate() : null;
        this.sectionId = d.sectionId();
        this.desiredStart = d.desiredStart();
        this.careType = d.careType();
        this.arrivesAt = d.arrivesAt();
        this.leavesAt = d.leavesAt();
        this.days.clear();
        this.days.addAll(d.days());
        this.guardianFirstName = d.guardianFirstName();
        this.guardianLastName = d.guardianLastName();
        this.relationship = d.relationship();
        this.phone = d.phone();
        this.email = d.email();
        this.familyId = d.familyId();
        this.criteria.clear();
        this.criteria.addAll(d.criteria());
    }

    /** Nouvelle étape et ses informations (relance, visite, réponse attendue, motif de refus). */
    public void moveTo(Stage stage, LocalDate on, LocalDate followUpOn, LocalDateTime visitAt, LocalDate offerExpiresOn,
                       String refusalReason) {
        if (stage != this.stage) {
            this.stageChangedOn = on;
        }
        this.stage = stage;
        this.followUpOn = stage == Stage.A_RECONTACTER ? followUpOn : null;
        if (stage == Stage.VISITE_PREVUE && visitAt != null && !visitAt.equals(this.visitAt)) {
            this.visitAt = visitAt;
            this.visitDone = false;
        }
        this.offerExpiresOn = stage == Stage.PLACE_PROPOSEE ? offerExpiresOn : null;
        this.refusalReason = stage == Stage.REFUSEE ? refusalReason : null;
    }

    public void markVisitDone() {
        this.visitDone = true;
    }

    public void linkChild(UUID childId, UUID familyId) {
        this.childId = childId;
        this.familyId = familyId;
    }

    /** Date de naissance connue ou prévue (l'enfant peut être à naître). */
    public LocalDate effectiveBirthDate() {
        return birthDate != null ? birthDate : expectedBirthDate;
    }

    public String childName() {
        return childFirstName + " " + childLastName;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public String getChildFirstName() {
        return childFirstName;
    }

    public String getChildLastName() {
        return childLastName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public LocalDate getExpectedBirthDate() {
        return expectedBirthDate;
    }

    public UUID getSectionId() {
        return sectionId;
    }

    public LocalDate getDesiredStart() {
        return desiredStart;
    }

    public CareType getCareType() {
        return careType;
    }

    public LocalTime getArrivesAt() {
        return arrivesAt;
    }

    public LocalTime getLeavesAt() {
        return leavesAt;
    }

    /** Jours triés (lundi d'abord). */
    public Set<Integer> getDays() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(days));
    }

    public Set<UUID> getCriteria() {
        return Set.copyOf(criteria);
    }

    public String getGuardianFirstName() {
        return guardianFirstName;
    }

    public String getGuardianLastName() {
        return guardianLastName;
    }

    public String getRelationship() {
        return relationship;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Stage getStage() {
        return stage;
    }

    public LocalDate getStageChangedOn() {
        return stageChangedOn;
    }

    public LocalDate getFollowUpOn() {
        return followUpOn;
    }

    public LocalDateTime getVisitAt() {
        return visitAt;
    }

    public boolean isVisitDone() {
        return visitDone;
    }

    public LocalDate getOfferExpiresOn() {
        return offerExpiresOn;
    }

    public String getRefusalReason() {
        return refusalReason;
    }

    public UUID getChildId() {
        return childId;
    }

    public long getVersion() {
        return version;
    }
}
