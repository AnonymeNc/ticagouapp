package nc.ticagou.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nc.ticagou.accounting.AccountingSettingsService.Column;
import nc.ticagou.accounting.AccountingSettingsService.SettingsDto;
import nc.ticagou.accounting.JournalBuilder.CreditNote;
import nc.ticagou.accounting.JournalBuilder.Invoice;
import nc.ticagou.accounting.JournalBuilder.InvoiceLine;
import nc.ticagou.accounting.JournalBuilder.Journal;
import nc.ticagou.accounting.JournalBuilder.Kind;
import nc.ticagou.accounting.JournalBuilder.Line;
import nc.ticagou.accounting.JournalBuilder.Method;
import nc.ticagou.accounting.JournalBuilder.Party;
import nc.ticagou.accounting.JournalBuilder.Payment;
import nc.ticagou.accounting.JournalBuilder.ResolvedAccount;

class JournalBuilderTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    static final JournalBuilder.Accounts DEFAULTS = (role, day) -> Optional.of(new ResolvedAccount(role.defaultAccount(), role.label()));
    static final Party FAMILY = new Party(false, UUID.randomUUID(), "Sarah Wahéo");
    static final UUID PROVINCE_ID = UUID.randomUUID();
    static final Party PROVINCE = new Party(true, PROVINCE_ID, "Province Sud");

    static Invoice invoice(long familyTotal, InvoiceLine... lines) {
        return new Invoice("FA-2026-0001", DAY, FAMILY, familyTotal, List.of(lines));
    }

    static long sum(List<Line> lines, AccountRole role, boolean debit) {
        return lines.stream().filter(l -> l.role() == role).mapToLong(l -> debit ? l.debit() : l.credit()).sum();
    }

    @Test
    void factureAvecAideEtAbsenceEquilibree() {
        var result = JournalBuilder.build(DEFAULTS, List.of(invoice(80_000,
                new InvoiceLine(Kind.FORFAIT, 90_000, 0, null, null),
                new InvoiceLine(Kind.HEURES_COMPLEMENTAIRES, 3_100, 0, null, null),
                new InvoiceLine(Kind.ABSENCE, -4_500, 0, null, null),
                new InvoiceLine(Kind.FRAIS_INSCRIPTION, 10_000, 0, null, null),
                new InvoiceLine(Kind.AIDE, -18_600, 0, PROVINCE_ID, "Province Sud"))), List.of(), List.of());
        var lines = result.lines();
        assertThat(result.anomalies()).isEmpty();
        assertThat(result.debits()).isEqualTo(result.credits()).isEqualTo(98_600);
        assertThat(sum(lines, AccountRole.CLIENTS_FAMILLES, true)).isEqualTo(80_000);
        assertThat(sum(lines, AccountRole.CLIENTS_FINANCEURS, true)).isEqualTo(18_600);
        assertThat(sum(lines, AccountRole.PRODUITS_GARDE, false)).as("forfait moins l'absence").isEqualTo(85_500);
        assertThat(sum(lines, AccountRole.PRODUITS_SUPPLEMENTS, false)).isEqualTo(3_100);
        assertThat(sum(lines, AccountRole.PRODUITS_INSCRIPTION, false)).isEqualTo(10_000);
        assertThat(lines).noneMatch(l -> l.role() == AccountRole.ARRONDIS);
        assertThat(lines).allMatch(l -> l.journal() == Journal.VENTES && l.piece().equals("FA-2026-0001"));
        assertThat(lines.stream().filter(l -> l.role() == AccountRole.CLIENTS_FINANCEURS).findFirst().orElseThrow().party())
                .isEqualTo(PROVINCE);
    }

    @Test
    void ecartDArrondiPorteAuCompteDArrondis() {
        // 45 123 F arrondis à 45 120 F (pas de 10, au plus proche) : 3 F au débit des arrondis.
        var result = JournalBuilder.build(DEFAULTS, List.of(invoice(45_120,
                new InvoiceLine(Kind.FORFAIT, 45_123, 0, null, null))), List.of(), List.of());
        assertThat(result.debits()).isEqualTo(result.credits()).isEqualTo(45_123);
        assertThat(sum(result.lines(), AccountRole.ARRONDIS, true)).isEqualTo(3);
    }

    @Test
    void tgcAuCreditDuCompteDeTgc() {
        var result = JournalBuilder.build(DEFAULTS, List.of(invoice(10_300,
                new InvoiceLine(Kind.FORFAIT, 10_000, 300, null, null))), List.of(), List.of());
        assertThat(sum(result.lines(), AccountRole.TGC_COLLECTEE, false)).isEqualTo(300);
        assertThat(result.debits()).isEqualTo(result.credits());
    }

    @Test
    void avoirEtEncaissementsParMoyenDePaiement() {
        var note = new CreditNote("AV-2026-0001", "FA-2026-0001", DAY, FAMILY, 2_500, Map.of(PROVINCE, 500L));
        var payments = List.of(
                new Payment("R-2026-0001", DAY, FAMILY, Method.VIREMENT, 30_000, "FA-2026-0001", false),
                new Payment("R-2026-0002", DAY, FAMILY, Method.ESPECES, 5_000, "FA-2026-0001", false),
                new Payment("R-2026-0003", DAY, FAMILY, Method.CHEQUE, 7_000, null, false),
                new Payment("R-2026-0003-ANN", DAY.plusDays(1), FAMILY, Method.CHEQUE, 7_000, null, true));
        var result = JournalBuilder.build(DEFAULTS, List.of(), List.of(note), payments);
        var lines = result.lines();
        assertThat(result.debits()).isEqualTo(result.credits());
        assertThat(sum(lines, AccountRole.AVOIRS, true)).isEqualTo(3_000);
        assertThat(sum(lines, AccountRole.CLIENTS_FINANCEURS, false)).isEqualTo(500);
        assertThat(sum(lines, AccountRole.BANQUE, true)).isEqualTo(30_000);
        assertThat(lines.stream().filter(l -> l.role() == AccountRole.CAISSE).findFirst().orElseThrow().journal())
                .isEqualTo(Journal.CAISSE);
        assertThat(sum(lines, AccountRole.CHEQUES, true)).as("chèque encaissé puis annulé").isEqualTo(7_000);
        assertThat(sum(lines, AccountRole.CHEQUES, false)).isEqualTo(7_000);
        assertThat(lines.stream().filter(l -> l.piece().endsWith("-ANN")).findFirst().orElseThrow().label())
                .startsWith("Annulation règlement");
    }

    @Test
    void compteManquantSignale() {
        JournalBuilder.Accounts missing = (role, day) -> role == AccountRole.TGC_COLLECTEE ? Optional.empty() : DEFAULTS.at(role, day);
        var result = JournalBuilder.build(missing, List.of(invoice(10_300,
                new InvoiceLine(Kind.FORFAIT, 10_000, 300, null, null))), List.of(), List.of());
        assertThat(result.anomalies()).containsExactly("Aucun compte « TGC collectée » en vigueur le 03/11/2026 (Paramètres › Comptabilité).");
    }

    static SettingsDto settings(String separator, String date, List<Column> columns) {
        return new SettingsDto("VE", "BQ", "CA", "F", "O", separator, date, columns, true, 0);
    }

    @Test
    void fichierDesEcrituresComptables() {
        var result = JournalBuilder.build(DEFAULTS, List.of(invoice(81_400,
                new InvoiceLine(Kind.FORFAIT, 100_000, 0, null, null),
                new InvoiceLine(Kind.AIDE, -18_600, 0, PROVINCE_ID, "Province Sud"))), List.of(),
                List.of(new Payment("R-2026-0001", DAY, FAMILY, Method.VIREMENT, 81_400, "FA-2026-0001", false)));
        var codes = Map.of(FAMILY, "F00001", PROVINCE, "O00001");
        var text = new String(AccountingExporter.fec(result.lines(), settings(";", "JJ/MM/AAAA", List.of(Column.values())), codes),
                StandardCharsets.UTF_8);
        var rows = text.split("\r\n");
        assertThat(rows[0].split("\t")).hasSize(18).startsWith("JournalCode", "JournalLib", "EcritureNum");
        assertThat(rows).hasSize(1 + 3 + 2);
        var first = rows[1].split("\t", -1);
        assertThat(first).hasSize(18);
        assertThat(first[0]).isEqualTo("VE");
        assertThat(first[2]).isEqualTo("1");
        assertThat(first[3]).isEqualTo("20261103");
        assertThat(first[4]).isEqualTo("411100");
        assertThat(first[6]).isEqualTo("F00001");
        assertThat(first[11]).isEqualTo("81400,00");
        assertThat(first[12]).isEqualTo("0,00");
        assertThat(first[17]).isEqualTo("XPF");
        assertThat(rows[4].split("\t", -1)[0]).isEqualTo("BQ");
        assertThat(rows[4].split("\t", -1)[2]).as("nouvelle pièce, nouveau numéro").isEqualTo("2");
    }

    @Test
    void csvAuxColonnesChoisies() {
        var result = JournalBuilder.build(DEFAULTS, List.of(invoice(1_000,
                new InvoiceLine(Kind.FORFAIT, 1_000, 0, null, null))), List.of(), List.of());
        var text = new String(AccountingExporter.csv(result.lines(),
                settings("TAB", "AAAA-MM-JJ", List.of(Column.DATE, Column.COMPTE, Column.AUXILIAIRE, Column.DEBIT, Column.CREDIT)),
                Map.of(FAMILY, "F00001")), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Date\tCompte\tCompte auxiliaire\tDébit\tCrédit\r\n");
        assertThat(text).contains("2026-11-03\t411100\tF00001\t1000\t\r\n", "2026-11-03\t706100\t\t\t1000\r\n");
    }
}
