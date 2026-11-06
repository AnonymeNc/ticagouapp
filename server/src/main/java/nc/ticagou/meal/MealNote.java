package nc.ticagou.meal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Consigne du jour de la cuisine pour un enfant (« Pas de dorure à l'œuf sur le pain »). */
@Entity
@Table(name = "meal_note")
public class MealNote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(nullable = false, updatable = false)
    private LocalDate day;

    @Column(nullable = false)
    private String note;

    @Column(nullable = false)
    private String author;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MealNote() {
    }

    public MealNote(UUID structureId, UUID childId, LocalDate day) {
        this.structureId = structureId;
        this.childId = childId;
        this.day = day;
    }

    public void set(String note, String author, Instant at) {
        this.note = note;
        this.author = author;
        this.updatedAt = at;
    }

    public UUID getChildId() {
        return childId;
    }

    public String getNote() {
        return note;
    }
}
