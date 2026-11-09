package nc.ticagou.console.billing;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Contrats d'abonnement : une version par avenant, la dernière sans date de fin tant que le contrat court. */
@Repository
public class ContractRepository {

    public record Contract(UUID id, UUID crecheId, UUID clientId, UUID priceListId, String plan, int places, Set<String> modules,
                           int extraDesktops, int extraTablets, boolean premiumSupport, int engagementYears, String rhythm,
                           boolean extraStructure, int freeDiscount, String freeDiscountReason, LocalDate startsOn, LocalDate endsOn,
                           LocalDate signedOn) {

        public Pricing.Terms terms() {
            return new Pricing.Terms(plan, places, modules, extraDesktops, extraTablets, premiumSupport, engagementYears, extraStructure,
                    freeDiscount, freeDiscountReason);
        }

        public boolean covers(LocalDate day) {
            return !day.isBefore(startsOn) && (endsOn == null || !day.isAfter(endsOn));
        }
    }

    private final JdbcTemplate jdbc;

    public ContractRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Contract> forCreche(UUID crecheId) {
        return jdbc.query("SELECT * FROM subscription_contract WHERE creche_id = ? ORDER BY starts_on DESC, created_at DESC",
                ContractRepository::contract, crecheId);
    }

    /** Version qui couvre ce jour, sinon la dernière. */
    public Optional<Contract> current(UUID crecheId, LocalDate day) {
        var all = forCreche(crecheId);
        return all.stream().filter(c -> c.covers(day)).findFirst().or(() -> all.stream().findFirst());
    }

    public Optional<Contract> find(UUID id) {
        return jdbc.query("SELECT * FROM subscription_contract WHERE id = ?", ContractRepository::contract, id).stream().findFirst();
    }

    /** Contrats en cours : sans fin, ou finissant après {@code day}. */
    public List<Contract> running(LocalDate day) {
        return jdbc.query("SELECT * FROM subscription_contract WHERE ends_on IS NULL OR ends_on >= ? ORDER BY starts_on",
                ContractRepository::contract, Date.valueOf(day));
    }

    public void insert(Contract c, Instant now) {
        jdbc.update("""
                INSERT INTO subscription_contract (id, creche_id, client_id, price_list_id, plan, places, modules, extra_desktops,
                    extra_tablets, premium_support, engagement_years, rhythm, extra_structure, free_discount, free_discount_reason,
                    starts_on, ends_on, signed_on, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", c.id(), c.crecheId(), c.clientId(), c.priceListId(),
                c.plan(), c.places(), String.join(",", c.modules()), c.extraDesktops(), c.extraTablets(), c.premiumSupport(),
                c.engagementYears(), c.rhythm(), c.extraStructure(), c.freeDiscount(), c.freeDiscountReason(), Date.valueOf(c.startsOn()),
                c.endsOn() == null ? null : Date.valueOf(c.endsOn()), Date.valueOf(c.signedOn()), Timestamp.from(now), Timestamp.from(now));
    }

    /** Correction d'une version qu'aucune facture n'utilise encore. */
    public void update(Contract c, Instant now) {
        jdbc.update("""
                UPDATE subscription_contract SET price_list_id = ?, plan = ?, places = ?, modules = ?, extra_desktops = ?, extra_tablets = ?,
                    premium_support = ?, engagement_years = ?, rhythm = ?, extra_structure = ?, free_discount = ?, free_discount_reason = ?,
                    starts_on = ?, ends_on = ?, signed_on = ?, updated_at = ? WHERE id = ?""", c.priceListId(), c.plan(), c.places(),
                String.join(",", c.modules()), c.extraDesktops(), c.extraTablets(), c.premiumSupport(), c.engagementYears(), c.rhythm(),
                c.extraStructure(), c.freeDiscount(), c.freeDiscountReason(), Date.valueOf(c.startsOn()),
                c.endsOn() == null ? null : Date.valueOf(c.endsOn()), Date.valueOf(c.signedOn()), Timestamp.from(now), c.id());
    }

    public void close(UUID id, LocalDate endsOn, Instant now) {
        jdbc.update("UPDATE subscription_contract SET ends_on = ?, updated_at = ? WHERE id = ?", Date.valueOf(endsOn), Timestamp.from(now), id);
    }

    public boolean invoiced(UUID contractId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM invoice WHERE contract_id = ?)", Boolean.class, contractId));
    }

    private static Contract contract(ResultSet rs, int i) throws SQLException {
        var modules = rs.getString("modules");
        return new Contract(rs.getObject("id", UUID.class), rs.getObject("creche_id", UUID.class), rs.getObject("client_id", UUID.class),
                rs.getObject("price_list_id", UUID.class), rs.getString("plan"), rs.getInt("places"),
                modules == null || modules.isBlank() ? Set.of()
                        : new LinkedHashSet<>(Arrays.stream(modules.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList()),
                rs.getInt("extra_desktops"), rs.getInt("extra_tablets"), rs.getBoolean("premium_support"), rs.getInt("engagement_years"),
                rs.getString("rhythm"), rs.getBoolean("extra_structure"), rs.getInt("free_discount"), rs.getString("free_discount_reason"),
                rs.getDate("starts_on").toLocalDate(), CatalogueRepository.date(rs, "ends_on"), rs.getDate("signed_on").toLocalDate());
    }
}
