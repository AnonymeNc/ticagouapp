package nc.ticagou.meal;

import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import nc.ticagou.meal.KitchenAdvice.Course;

/** Plat d'un jour de la semaine ; une variante est rattachée à un régime. */
@Entity
@Table(name = "menu_item")
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "week_id", nullable = false, updatable = false)
    private UUID weekId;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Course course;

    @Column(name = "diet_id")
    private UUID dietId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String tags = "";

    protected MenuItem() {
    }

    public MenuItem(UUID structureId, UUID weekId, int dayOfWeek, Course course, UUID dietId, String label,
                    Set<String> tags) {
        this.structureId = structureId;
        this.weekId = weekId;
        this.dayOfWeek = dayOfWeek;
        this.course = course;
        this.dietId = dietId;
        this.label = label;
        this.tags = MealTags.format(tags);
    }

    public KitchenAdvice.Dish toDish() {
        return new KitchenAdvice.Dish(course, label, tagSet());
    }

    public Set<String> tagSet() {
        return MealTags.parse(tags);
    }

    public UUID getId() {
        return id;
    }

    public UUID getWeekId() {
        return weekId;
    }

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public Course getCourse() {
        return course;
    }

    public UUID getDietId() {
        return dietId;
    }

    public String getLabel() {
        return label;
    }
}
