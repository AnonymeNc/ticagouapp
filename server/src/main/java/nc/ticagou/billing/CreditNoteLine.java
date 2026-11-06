package nc.ticagou.billing;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "credit_note_line")
public class CreditNoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false)
    private UUID structureId;

    @Column(name = "credit_note_id", nullable = false)
    private UUID creditNoteId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private long amount;

    protected CreditNoteLine() {
    }

    public CreditNoteLine(CreditNote note, int position, String label, long amount) {
        this.structureId = note.getStructureId();
        this.creditNoteId = note.getId();
        this.position = position;
        this.label = label;
        this.amount = amount;
    }

    public UUID getCreditNoteId() {
        return creditNoteId;
    }

    public int getPosition() {
        return position;
    }

    public String getLabel() {
        return label;
    }

    public long getAmount() {
        return amount;
    }
}
