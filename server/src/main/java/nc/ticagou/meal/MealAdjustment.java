package nc.ticagou.meal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import nc.ticagou.meal.MealCounter.Meal;

/** Correction manuelle du comptage : repas ou goûter servi, ou non, à un enfant ce jour-là. */
@Entity
@Table(name = "meal_adjustment")
public class MealAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(nullable = false, updatable = false)
    private LocalDate day;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Meal meal;

    @Column(nullable = false)
    private boolean served;

    @Column(nullable = false)
    private String reason;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(nullable = false)
    private String author;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MealAdjustment() {
    }

    public MealAdjustment(UUID structureId, UUID childId, LocalDate day, Meal meal) {
        this.structureId = structureId;
        this.childId = childId;
        this.day = day;
        this.meal = meal;
    }

    public void set(boolean served, String reason, UUID authorId, String author, Instant at) {
        this.served = served;
        this.reason = reason;
        this.authorId = authorId;
        this.author = author;
        this.createdAt = at;
    }

    public UUID getChildId() {
        return childId;
    }

    public LocalDate getDay() {
        return day;
    }

    public Meal getMeal() {
        return meal;
    }

    public boolean isServed() {
        return served;
    }

    public String getReason() {
        return reason;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
