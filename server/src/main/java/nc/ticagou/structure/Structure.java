package nc.ticagou.structure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Crèche ou garderie : périmètre de toutes les données métier ({@code structure_id}). */
@Entity
@Table(name = "structure")
public class Structure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String city;

    /** RIDET : 7 chiffres, suivis du numéro d'établissement sur 3 chiffres. */
    private String ridet;

    @Column(name = "agrement_number")
    private String agrementNumber;

    @Column(name = "agrement_date")
    private LocalDate agrementDate;

    /** Capacité inscrite sur l'agrément ; la somme des places des sections ne peut pas la dépasser. */
    @Column(nullable = false)
    private int capacity;

    @Column(name = "invoice_footer")
    private String invoiceFooter;

    private byte[] logo;

    @Column(name = "logo_content_type")
    private String logoContentType;

    /** Prévenir la direction sur le PC du bureau en cas de dépassement du taux d’encadrement. */
    @Column(name = "ratio_alert_desktop", nullable = false)
    private boolean ratioAlertDesktop = true;

    /** Heure du repas : un repas est compté quand l'enfant est présent à cette heure. */
    @Column(name = "lunch_time", nullable = false)
    private java.time.LocalTime lunchTime = java.time.LocalTime.of(11, 30);

    @Column(name = "snack_time", nullable = false)
    private java.time.LocalTime snackTime = java.time.LocalTime.of(15, 30);

    private String address;

    @Column(name = "postal_code")
    private String postalCode;

    private String phone;

    private String email;

    @Column(name = "bank_holder")
    private String bankHolder;

    @Column(name = "bank_name")
    private String bankName;

    private String iban;

    private String bic;

    /** Imprimer le RIB en bas de chaque facture. */
    @Column(name = "print_rib", nullable = false)
    private boolean printRib;

    /** Proposer le passage dans la section suivante dès que l'enfant atteint l'âge de sortie. */
    @Column(name = "passage_propose", nullable = false)
    private boolean passagePropose = true;

    /** Regrouper les passages au 1er de chaque mois. */
    @Column(name = "passage_first_of_month", nullable = false)
    private boolean passageFirstOfMonth = true;

    /** Bloquer un passage si la section d'arrivée est complète. */
    @Column(name = "passage_block_full", nullable = false)
    private boolean passageBlockFull = true;

    @ElementCollection
    @CollectionTable(name = "structure_opening_hours", joinColumns = @JoinColumn(name = "structure_id"))
    @OrderBy("dayOfWeek")
    private List<OpeningHours> openingHours = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Structure() {
    }

    public Structure(String name, int capacity) {
        this.name = name;
        this.capacity = capacity;
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

    public void updateIdentity(String name, String city, String ridet, String agrementNumber, LocalDate agrementDate,
                               int capacity, String invoiceFooter) {
        this.name = name;
        this.city = city;
        this.ridet = ridet;
        this.agrementNumber = agrementNumber;
        this.agrementDate = agrementDate;
        this.capacity = capacity;
        this.invoiceFooter = invoiceFooter;
    }

    public void replaceOpeningHours(List<OpeningHours> hours) {
        openingHours.clear();
        openingHours.addAll(hours);
    }

    public void replaceLogo(byte[] content, String contentType) {
        this.logo = content;
        this.logoContentType = contentType;
    }

    public void removeLogo() {
        replaceLogo(null, null);
    }

    public void setRatioAlertDesktop(boolean ratioAlertDesktop) {
        this.ratioAlertDesktop = ratioAlertDesktop;
    }

    public boolean isRatioAlertDesktop() {
        return ratioAlertDesktop;
    }

    public void setMealTimes(java.time.LocalTime lunchTime, java.time.LocalTime snackTime) {
        this.lunchTime = lunchTime;
        this.snackTime = snackTime;
    }

    public java.time.LocalTime getLunchTime() {
        return lunchTime;
    }

    public java.time.LocalTime getSnackTime() {
        return snackTime;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }

    public String getRidet() {
        return ridet;
    }

    public String getAgrementNumber() {
        return agrementNumber;
    }

    public LocalDate getAgrementDate() {
        return agrementDate;
    }

    public int getCapacity() {
        return capacity;
    }

    public String getInvoiceFooter() {
        return invoiceFooter;
    }

    public byte[] getLogo() {
        return logo;
    }

    public String getLogoContentType() {
        return logoContentType;
    }

    public boolean hasLogo() {
        return logo != null;
    }

    public List<OpeningHours> getOpeningHours() {
        return List.copyOf(openingHours);
    }

    public void updateContact(String address, String postalCode, String phone, String email) {
        this.address = address;
        this.postalCode = postalCode;
        this.phone = phone;
        this.email = email;
    }

    public void updateBank(String bankHolder, String bankName, String iban, String bic, boolean printRib) {
        this.bankHolder = bankHolder;
        this.bankName = bankName;
        this.iban = iban;
        this.bic = bic;
        this.printRib = printRib;
    }

    public void updatePassageRules(boolean propose, boolean firstOfMonth, boolean blockFull) {
        this.passagePropose = propose;
        this.passageFirstOfMonth = firstOfMonth;
        this.passageBlockFull = blockFull;
    }

    public String getAddress() {
        return address;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getBankHolder() {
        return bankHolder;
    }

    public String getBankName() {
        return bankName;
    }

    public String getIban() {
        return iban;
    }

    public String getBic() {
        return bic;
    }

    public boolean isPrintRib() {
        return printRib;
    }

    public boolean isPassagePropose() {
        return passagePropose;
    }

    public boolean isPassageFirstOfMonth() {
        return passageFirstOfMonth;
    }

    public boolean isPassageBlockFull() {
        return passageBlockFull;
    }

    public long getVersion() {
        return version;
    }
}
