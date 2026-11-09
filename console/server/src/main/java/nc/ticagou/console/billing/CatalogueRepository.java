package nc.ticagou.console.billing;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import nc.ticagou.console.billing.Pricing.Catalogue;
import nc.ticagou.console.billing.Pricing.PriceItem;
import nc.ticagou.console.billing.Pricing.PriceList;

/** Barème (versions datées et prix), taux de TGC datés, mentions de l'éditeur. */
@Repository
public class CatalogueRepository {

    public record TgcRate(UUID id, String nature, int rate, LocalDate validFrom, LocalDate validTo, String note) {
    }

    public record Issuer(String name, String legalForm, String ridet, String tgcNumber, String address, String email, String phone,
                         String bank, String iban, String paymentTerms, String latePenalties, String footer, String copyEmail) {
    }

    private final JdbcTemplate jdbc;

    public CatalogueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Barème

    public List<PriceList> priceLists() {
        return jdbc.query("SELECT * FROM price_list ORDER BY valid_from DESC", CatalogueRepository::priceList);
    }

    public Optional<PriceList> priceList(UUID id) {
        return jdbc.query("SELECT * FROM price_list WHERE id = ?", CatalogueRepository::priceList, id).stream().findFirst();
    }

    /** Version du barème en vigueur ce jour-là. */
    public Optional<PriceList> priceListAt(LocalDate day) {
        return jdbc.query("""
                SELECT * FROM price_list WHERE valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)
                ORDER BY valid_from DESC LIMIT 1""", CatalogueRepository::priceList, Date.valueOf(day), Date.valueOf(day))
                .stream().findFirst();
    }

    public List<PriceItem> items(UUID priceListId) {
        return jdbc.query("SELECT * FROM price_item WHERE price_list_id = ? ORDER BY position, code, tier",
                (rs, i) -> new PriceItem(rs.getString("kind"), rs.getString("code"), rs.getString("tier"), rs.getString("label"),
                        rs.getLong("price"), rs.getString("unit"), rs.getString("includes"), rs.getString("tgc_nature")), priceListId);
    }

    public Catalogue catalogue(UUID priceListId) {
        var list = priceList(priceListId).orElseThrow();
        return new Catalogue(list, items(priceListId));
    }

    /** Nouvelle version : la précédente (sans fin) s'arrête la veille. */
    public void insertPriceList(PriceList list, List<PriceItem> items, Instant now) {
        jdbc.update("UPDATE price_list SET valid_to = ? WHERE valid_to IS NULL AND valid_from < ?",
                Date.valueOf(list.validFrom().minusDays(1)), Date.valueOf(list.validFrom()));
        jdbc.update("""
                INSERT INTO price_list (id, label, valid_from, valid_to, micro_max, structure_max, grande_max, included_desktops,
                    included_tablets, engagement3_discount, extra_structure_discount, created_at)
                VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)""", list.id(), list.label(), Date.valueOf(list.validFrom()),
                list.microMax(), list.structureMax(), list.grandeMax(), list.includedDesktops(), list.includedTablets(),
                list.engagement3Discount(), list.extraStructureDiscount(), Timestamp.from(now));
        int position = 0;
        for (var item : items) {
            jdbc.update("""
                    INSERT INTO price_item (id, price_list_id, kind, code, tier, label, price, unit, includes, tgc_nature, position)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), list.id(), item.kind(), item.code(), item.tier(),
                    item.label(), item.price(), item.unit(), item.includes(), item.tgcNature(), position++);
        }
    }

    public boolean priceListStartsOn(LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM price_list WHERE valid_from >= ?)", Boolean.class,
                Date.valueOf(day)));
    }

    // --- TGC

    public List<TgcRate> tgcRates() {
        return jdbc.query("SELECT * FROM tgc_rate ORDER BY nature, valid_from DESC", (rs, i) -> new TgcRate(rs.getObject("id", UUID.class),
                rs.getString("nature"), rs.getInt("rate"), rs.getDate("valid_from").toLocalDate(), date(rs, "valid_to"), rs.getString("note")));
    }

    public Optional<Integer> rateAt(String nature, LocalDate day) {
        return jdbc.queryForList("""
                SELECT rate FROM tgc_rate WHERE nature = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)
                ORDER BY valid_from DESC LIMIT 1""", Integer.class, nature, Date.valueOf(day), Date.valueOf(day)).stream().findFirst();
    }

    /** Nouveau taux à partir d'une date : le taux précédent de cette nature s'arrête la veille. */
    public void insertRate(String nature, int rate, LocalDate from, String note) {
        jdbc.update("UPDATE tgc_rate SET valid_to = ? WHERE nature = ? AND valid_to IS NULL AND valid_from < ?",
                Date.valueOf(from.minusDays(1)), nature, Date.valueOf(from));
        jdbc.update("INSERT INTO tgc_rate (id, nature, rate, valid_from, note) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), nature, rate,
                Date.valueOf(from), note);
    }

    public boolean rateStartsOnOrAfter(String nature, LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM tgc_rate WHERE nature = ? AND valid_from >= ?)",
                Boolean.class, nature, Date.valueOf(day)));
    }

    // --- Éditeur

    public Issuer issuer() {
        return jdbc.queryForObject("SELECT * FROM issuer WHERE id = 1", (rs, i) -> new Issuer(rs.getString("name"), rs.getString("legal_form"),
                rs.getString("ridet"), rs.getString("tgc_number"), rs.getString("address"), rs.getString("email"), rs.getString("phone"),
                rs.getString("bank"), rs.getString("iban"), rs.getString("payment_terms"), rs.getString("late_penalties"),
                rs.getString("footer"), rs.getString("copy_email")));
    }

    public void updateIssuer(Issuer i, Instant now) {
        jdbc.update("""
                UPDATE issuer SET name = ?, legal_form = ?, ridet = ?, tgc_number = ?, address = ?, email = ?, phone = ?, bank = ?,
                    iban = ?, payment_terms = ?, late_penalties = ?, footer = ?, copy_email = ?, updated_at = ? WHERE id = 1""",
                i.name(), i.legalForm(), i.ridet(), i.tgcNumber(), i.address(), i.email(), i.phone(), i.bank(), i.iban(),
                i.paymentTerms(), i.latePenalties(), i.footer(), i.copyEmail(), Timestamp.from(now));
    }

    // --- Outils

    private static PriceList priceList(ResultSet rs, int i) throws SQLException {
        return new PriceList(rs.getObject("id", UUID.class), rs.getString("label"), rs.getDate("valid_from").toLocalDate(),
                date(rs, "valid_to"), rs.getInt("micro_max"), rs.getInt("structure_max"), rs.getInt("grande_max"),
                rs.getInt("included_desktops"), rs.getInt("included_tablets"), rs.getInt("engagement3_discount"),
                rs.getInt("extra_structure_discount"));
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        var d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }
}
