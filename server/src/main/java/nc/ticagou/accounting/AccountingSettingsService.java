package nc.ticagou.accounting;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.CounterService;

/**
 * Paramètres de l'export comptable : un compte daté par rôle (on n'écrase pas l'ancien, on en crée un nouveau avec
 * sa date de début), les codes journaux, les préfixes des comptes auxiliaires et le format du CSV. Les valeurs par
 * défaut sont semées au premier accès d'une structure.
 */
@Service
public class AccountingSettingsService {

    /** Colonnes disponibles pour le CSV, dans l'ordre par défaut. */
    public enum Column { JOURNAL, DATE, PIECE, COMPTE, AUXILIAIRE, LIBELLE, DEBIT, CREDIT }

    public record AccountDto(UUID id, AccountRole role, String roleLabel, String account, String label,
                             LocalDate validFrom, LocalDate validTo, long version) {
    }

    public record AccountRequest(
            @NotNull AccountRole role,
            @NotBlank @Pattern(regexp = "^[0-9A-Z]{3,20}$", message = "Numéro de compte : 3 à 20 chiffres ou lettres majuscules") String account,
            @NotBlank @Size(max = 100) String label,
            @NotNull LocalDate validFrom,
            LocalDate validTo) {
    }

    public record SettingsDto(
            @NotBlank @Size(max = 6) String salesJournal,
            @NotBlank @Size(max = 6) String bankJournal,
            @NotBlank @Size(max = 6) String cashJournal,
            @NotBlank @Size(max = 6) @Pattern(regexp = "^[0-9A-Z]*$", message = "Préfixe : chiffres ou lettres majuscules") String familyPrefix,
            @NotBlank @Size(max = 6) @Pattern(regexp = "^[0-9A-Z]*$", message = "Préfixe : chiffres ou lettres majuscules") String funderPrefix,
            @NotNull @Pattern(regexp = ";|,|TAB", message = "Séparateur : « ; », « , » ou TAB") String csvSeparator,
            @NotNull @Pattern(regexp = "JJ/MM/AAAA|AAAA-MM-JJ|AAAAMMJJ", message = "Format de date inconnu") String csvDate,
            @NotEmpty @Size(max = 8) List<@NotNull Column> csvColumns,
            boolean csvHeader,
            long version) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CounterService counters;
    private final Clock clock;

    public AccountingSettingsService(JdbcTemplate jdbc, AuditService audit, CounterService counters, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.counters = counters;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- comptes

    @Transactional
    public List<AccountDto> accounts() {
        var structureId = CurrentUser.get().structureId();
        ensureDefaults(structureId);
        return jdbc.query("""
                SELECT id, role, account, label, valid_from, valid_to, version FROM accounting_account
                WHERE structure_id = ? ORDER BY role, valid_from DESC""", (rs, i) -> {
            var role = AccountRole.valueOf(rs.getString("role"));
            var to = rs.getDate("valid_to");
            return new AccountDto(rs.getObject("id", UUID.class), role, role.label(), rs.getString("account"),
                    rs.getString("label"), rs.getDate("valid_from").toLocalDate(), to == null ? null : to.toLocalDate(),
                    rs.getLong("version"));
        }, structureId);
    }

    @Transactional
    public AccountDto create(AccountRequest request) {
        var structureId = CurrentUser.get().structureId();
        ensureDefaults(structureId);
        checkOverlap(structureId, null, request);
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounting_account (id, structure_id, role, account, label, valid_from, valid_to)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", id, structureId, request.role().name(), request.account(), request.label(),
                Date.valueOf(request.validFrom()), request.validTo() == null ? null : Date.valueOf(request.validTo()));
        audit.record(AuditAction.ACCOUNTING_ACCOUNT_CREATED, "accounting_account", id,
                Map.of("role", request.role().name(), "account", request.account(), "validFrom", request.validFrom().toString()));
        return find(structureId, id);
    }

    @Transactional
    public AccountDto update(UUID id, AccountRequest request, long version) {
        var structureId = CurrentUser.get().structureId();
        checkOverlap(structureId, id, request);
        int updated = jdbc.update("""
                UPDATE accounting_account SET role = ?, account = ?, label = ?, valid_from = ?, valid_to = ?, version = version + 1
                WHERE id = ? AND structure_id = ? AND version = ?""", request.role().name(), request.account(),
                request.label(), Date.valueOf(request.validFrom()),
                request.validTo() == null ? null : Date.valueOf(request.validTo()), id, structureId, version);
        if (updated == 0) {
            throw ApiException.conflict("stale", "Ce compte a été modifié entre-temps : rechargez la page.");
        }
        audit.record(AuditAction.ACCOUNTING_ACCOUNT_UPDATED, "accounting_account", id,
                Map.of("role", request.role().name(), "account", request.account()));
        return find(structureId, id);
    }

    private AccountDto find(UUID structureId, UUID id) {
        return accounts().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Compte introuvable."));
    }

    private void checkOverlap(UUID structureId, UUID self, AccountRequest request) {
        if (request.validTo() != null && request.validTo().isBefore(request.validFrom())) {
            throw ApiException.rule("accounting_period", "La fin de validité ne peut pas précéder le début.");
        }
        var others = jdbc.queryForList("""
                SELECT valid_from, valid_to FROM accounting_account WHERE structure_id = ? AND role = ? AND id <> ?""",
                structureId, request.role().name(), self == null ? new UUID(0, 0) : self);
        for (var other : others) {
            var from = ((Date) other.get("valid_from")).toLocalDate();
            var to = other.get("valid_to") == null ? null : ((Date) other.get("valid_to")).toLocalDate();
            if (overlaps(from, to, request.validFrom(), request.validTo())) {
                throw ApiException.rule("accounting_overlap", ("Un autre compte « %s » est déjà en vigueur sur cette période : "
                        + "terminez-le (date de fin) avant d’en ouvrir un nouveau.").formatted(request.role().label()));
            }
        }
    }

    static boolean overlaps(LocalDate from1, LocalDate to1, LocalDate from2, LocalDate to2) {
        return (to2 == null || !from1.isAfter(to2)) && (to1 == null || !from2.isAfter(to1));
    }

    /** Compte de chaque rôle en vigueur à une date, pour une structure (export). */
    JournalBuilder.Accounts resolver(UUID structureId) {
        ensureDefaults(structureId);
        var rows = jdbc.queryForList("SELECT role, account, label, valid_from, valid_to FROM accounting_account WHERE structure_id = ?",
                structureId);
        return (role, day) -> rows.stream()
                .filter(r -> r.get("role").equals(role.name()))
                .filter(r -> !day.isBefore(((Date) r.get("valid_from")).toLocalDate())
                        && (r.get("valid_to") == null || !day.isAfter(((Date) r.get("valid_to")).toLocalDate())))
                .findFirst()
                .map(r -> new JournalBuilder.ResolvedAccount((String) r.get("account"), (String) r.get("label")));
    }

    // ------------------------------------------------------------------------------------------- réglages

    @Transactional
    public SettingsDto settings() {
        return settings(CurrentUser.get().structureId());
    }

    SettingsDto settings(UUID structureId) {
        ensureDefaults(structureId);
        return jdbc.queryForObject("SELECT * FROM accounting_settings WHERE structure_id = ?", (rs, i) -> new SettingsDto(
                rs.getString("sales_journal"), rs.getString("bank_journal"), rs.getString("cash_journal"),
                rs.getString("family_prefix"), rs.getString("funder_prefix"), rs.getString("csv_separator"),
                rs.getString("csv_date"), Arrays.stream(rs.getString("csv_columns").split(",")).map(Column::valueOf).toList(),
                rs.getBoolean("csv_header"), rs.getLong("version")), structureId);
    }

    @Transactional
    public SettingsDto updateSettings(SettingsDto request) {
        var structureId = CurrentUser.get().structureId();
        ensureDefaults(structureId);
        if (request.csvColumns().stream().distinct().count() != request.csvColumns().size()) {
            throw ApiException.rule("accounting_columns", "Chaque colonne du CSV ne peut figurer qu’une fois.");
        }
        int updated = jdbc.update("""
                UPDATE accounting_settings SET sales_journal = ?, bank_journal = ?, cash_journal = ?, family_prefix = ?,
                       funder_prefix = ?, csv_separator = ?, csv_date = ?, csv_columns = ?, csv_header = ?, updated_at = ?,
                       version = version + 1
                WHERE structure_id = ? AND version = ?""",
                request.salesJournal().trim().toUpperCase(), request.bankJournal().trim().toUpperCase(),
                request.cashJournal().trim().toUpperCase(), request.familyPrefix(), request.funderPrefix(),
                request.csvSeparator(), request.csvDate(),
                String.join(",", request.csvColumns().stream().map(Enum::name).toList()), request.csvHeader(),
                Timestamp.from(clock.instant()), structureId, request.version());
        if (updated == 0) {
            throw ApiException.conflict("stale", "Les réglages ont été modifiés entre-temps : rechargez la page.");
        }
        audit.record(AuditAction.ACCOUNTING_SETTINGS_UPDATED, "accounting_settings", structureId, Map.of());
        return settings(structureId);
    }

    // ------------------------------------------------------------------------------------------- auxiliaires

    /** Compte auxiliaire stable d'une famille ou d'un financeur : « F00012 », attribué au premier export. */
    String partyCode(UUID structureId, JournalBuilder.Party party, SettingsDto settings) {
        var type = party.funder() ? "FINANCEUR" : "FAMILLE";
        var existing = jdbc.queryForList("SELECT code FROM accounting_party WHERE structure_id = ? AND party_type = ? AND party_id = ?",
                String.class, structureId, type, party.id());
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        var prefix = party.funder() ? settings.funderPrefix() : settings.familyPrefix();
        var code = "%s%05d".formatted(prefix, counters.next(structureId, "accounting-" + type.toLowerCase(), 0));
        jdbc.update("INSERT INTO accounting_party (structure_id, party_type, party_id, code) VALUES (?, ?, ?, ?)",
                structureId, type, party.id(), code);
        return code;
    }

    // ------------------------------------------------------------------------------------------- défauts

    /** Plan de comptes et réglages proposés, semés une fois par structure (depuis le 1er janvier 2020). */
    void ensureDefaults(UUID structureId) {
        int settings = jdbc.update("""
                INSERT INTO accounting_settings (structure_id, sales_journal, bank_journal, cash_journal, family_prefix,
                       funder_prefix, csv_separator, csv_date, csv_columns, csv_header, updated_at)
                VALUES (?, 'VE', 'BQ', 'CA', 'F', 'O', ';', 'JJ/MM/AAAA', ?, TRUE, ?) ON CONFLICT (structure_id) DO NOTHING""",
                structureId, String.join(",", Arrays.stream(Column.values()).map(Enum::name).toList()),
                Timestamp.from(clock.instant()));
        if (settings == 0) {
            return;
        }
        for (var role : AccountRole.values()) {
            jdbc.update("""
                    INSERT INTO accounting_account (id, structure_id, role, account, label, valid_from)
                    VALUES (?, ?, ?, ?, ?, DATE '2020-01-01')""", UUID.randomUUID(), structureId, role.name(),
                    role.defaultAccount(), role.label());
        }
    }

}
