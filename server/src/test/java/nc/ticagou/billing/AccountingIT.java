package nc.ticagou.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Export comptable d'un mois facturé : factures avec aide, avoir, encaissements ; CSV et FEC équilibrés. */
class AccountingIT extends BillingFixtures {

    String period;

    @BeforeEach
    void factureDuMois() throws Exception {
        var province = funder("Province Sud", "POURCENTAGE", 0, 200, 30_000L, 1);
        grant(lea, province, "Famille");
        generated();
        var lea = invoiceOf("Famille Wamytan");
        var tom = invoiceOf("Famille Poédi");
        var payment = new LinkedHashMap<String, Object>();
        payment.put("invoiceId", lea);
        payment.put("paidOn", TODAY.toString());
        payment.put("amount", 20_000);
        payment.put("method", "VIREMENT");
        payment.put("reference", "VIR-1");
        assertThat(status(perform(post("/api/v1/billing/payments"), director, payment))).isEqualTo(201);
        var credit = perform(post("/api/v1/billing/invoices/" + tom + "/credit-notes"), director,
                Map.of("reason", "Journée pédagogique", "full", false, "lines", List.of(Map.of("label", "Journée", "amount", 2_500))));
        assertThat(status(credit)).as(credit.getResponse().getContentAsString()).isEqualTo(201);
        period = "from=" + TODAY.withDayOfMonth(1) + "&to=" + TODAY;
    }

    @Test
    void controleEquilibreEtFec() throws Exception {
        var check = body(getAs(director, "/api/v1/accounting/check?" + period));
        assertThat(check.get("anomalies")).isEmpty();
        assertThat(check.get("debit").asLong()).isEqualTo(check.get("credit").asLong()).isPositive();
        assertThat(check.get("journals").findValuesAsText("journal")).containsExactly("VE", "BQ");
        assertThat(check.at("/journals/0/pieces").asInt()).as("2 factures et 1 avoir").isEqualTo(3);

        var result = getAs(director, "/api/v1/accounting/export?" + period + "&format=FEC");
        assertThat(status(result)).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("1234567001FEC");
        var rows = result.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\r\n");
        assertThat(rows[0].split("\t")).hasSize(18);
        long debit = 0;
        long credit = 0;
        for (var row : Arrays.copyOfRange(rows, 1, rows.length)) {
            var cells = row.split("\t", -1);
            assertThat(cells).hasSize(18);
            debit += Long.parseLong(cells[11].replace(",00", ""));
            credit += Long.parseLong(cells[12].replace(",00", ""));
        }
        assertThat(debit).isEqualTo(credit).isEqualTo(check.get("debit").asLong());
        var text = String.join("\n", rows);
        assertThat(text).contains("\t411200\t", "\tO00001\tProvince Sud\t", "\t709000\t", "AV-");
        assertThat(auditCount("ACCOUNTING_EXPORTED")).isEqualTo(1);

        // Les comptes auxiliaires restent les mêmes d'un export à l'autre.
        var again = getAs(director, "/api/v1/accounting/export?" + period + "&format=FEC").getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(again).isEqualTo(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void csvParametreEtCompteDate() throws Exception {
        var settings = body(getAs(director, "/api/v1/accounting/settings"));
        var update = json.convertValue(settings, Map.class);
        update.put("csvSeparator", "TAB");
        update.put("csvColumns", List.of("DATE", "PIECE", "COMPTE", "DEBIT", "CREDIT"));
        assertThat(status(perform(put("/api/v1/accounting/settings"), director, update))).isEqualTo(200);
        var csv = getAs(director, "/api/v1/accounting/export?" + period + "&format=CSV").getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Date\tPièce\tCompte\tDébit\tCrédit\r\n").contains("\t706100\t");

        // Nouveau compte de garde à partir du 1er du mois : l'ancien doit d'abord être terminé.
        var garde = new LinkedHashMap<String, Object>(Map.of("role", "PRODUITS_GARDE", "account", "706110",
                "label", "Garde régulière", "validFrom", TODAY.withDayOfMonth(1).toString()));
        var overlap = perform(post("/api/v1/accounting/accounts"), director, garde);
        assertThat(body(overlap).get("code").asText()).isEqualTo("accounting_overlap");
        var old = body(getAs(director, "/api/v1/accounting/accounts")).findParents("role").stream()
                .filter(a -> a.get("role").asText().equals("PRODUITS_GARDE")).findFirst().orElseThrow();
        var end = new LinkedHashMap<String, Object>(Map.of("role", "PRODUITS_GARDE", "account", "706100",
                "label", old.get("label").asText(), "validFrom", old.get("validFrom").asText(),
                "validTo", TODAY.withDayOfMonth(1).minusDays(1).toString()));
        assertThat(status(perform(put("/api/v1/accounting/accounts/" + old.get("id").asText() + "?version=0"), director, end)))
                .isEqualTo(200);
        assertThat(status(perform(post("/api/v1/accounting/accounts"), director, garde))).isEqualTo(201);
        var after = getAs(director, "/api/v1/accounting/export?" + period + "&format=CSV").getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(after).contains("\t706110\t").doesNotContain("\t706100\t");
    }

    @Test
    void compteManquantBloqueLExport() throws Exception {
        body(getAs(director, "/api/v1/accounting/accounts"));
        jdbc.update("DELETE FROM accounting_account WHERE role = 'TGC_COLLECTEE'");
        // Sans TGC (taux à 0 %), aucune ligne de TGC : l'export passe. Un compte de client manquant le bloque.
        jdbc.update("DELETE FROM accounting_account WHERE role = 'CLIENTS_FAMILLES'");
        var check = body(getAs(director, "/api/v1/accounting/check?" + period));
        assertThat(check.get("anomalies").get(0).asText()).contains("Clients — familles");
        var refused = getAs(director, "/api/v1/accounting/export?" + period + "&format=FEC");
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).get("code").asText()).isEqualTo("accounting_anomalies");
    }

    @Test
    void reserveALaDirectionEtALaComptabilite() throws Exception {
        createUser(director, "EDUCATRICE", "lea.wema", "Educatrice-2026!", null);
        createUser(director, "COMPTABILITE", "compta", "Comptable-2026!", null);
        assertThat(status(getAs(accessToken("lea.wema", "Educatrice-2026!"), "/api/v1/accounting/check?" + period))).isEqualTo(403);
        assertThat(status(getAs(accessToken("compta", "Comptable-2026!"), "/api/v1/accounting/check?" + period))).isEqualTo(200);
    }
}
