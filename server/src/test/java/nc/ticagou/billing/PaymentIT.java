package nc.ticagou.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

class PaymentIT extends BillingFixtures {

    String invoiceId;
    long total;

    @BeforeEach
    void invoiced() throws Exception {
        generated();
        invoiceId = invoiceOf("Famille Wamytan");
        total = invoice().at("/summary/familyTotal").asLong();
    }

    JsonNode invoice() throws Exception {
        return body(getAs(director, "/api/v1/billing/invoices/" + invoiceId));
    }

    MvcResult pay(long amount, String method, String reference) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("invoiceId", invoiceId);
        request.put("paidOn", TODAY.toString());
        request.put("amount", amount);
        request.put("method", method);
        request.put("reference", reference);
        request.put("bank", "BCI");
        return perform(post("/api/v1/billing/payments"), director, request);
    }

    JsonNode paid(long amount, String method, String reference) throws Exception {
        var result = pay(amount, method, reference);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result);
    }

    /** Échéance reculée pour simuler un impayé ancien (le trigger d'immuabilité est suspendu le temps du test). */
    void overdueBy(int days) {
        jdbc.execute("ALTER TABLE invoice DISABLE TRIGGER invoice_immutable");
        try {
            jdbc.update("UPDATE invoice SET issued_on = ?, due_on = ? WHERE id = ?::uuid", TODAY.minusDays(days + 10),
                    TODAY.minusDays(days), invoiceId);
        } finally {
            jdbc.execute("ALTER TABLE invoice ENABLE TRIGGER invoice_immutable");
        }
    }

    @Test
    void paiementPartielPuisSoldeAvecLettragePlafonne() throws Exception {
        assertThat(status(pay(10_000, "CHEQUE", null))).as("numéro du chèque exigé").isEqualTo(400);

        var cheque = paid(10_000, "CHEQUE", "0004521");
        assertThat(cheque.get("bank").asText()).isEqualTo("BCI");
        assertThat(cheque.at("/allocations/0/amount").asLong()).isEqualTo(10_000);
        assertThat(invoice().at("/summary/status").asText()).isEqualTo("PARTIELLE");
        assertThat(invoice().at("/summary/remaining").asLong()).isEqualTo(total - 10_000);

        var tooMuch = pay(total, "VIREMENT", "VIR-1");
        assertThat(status(tooMuch)).isEqualTo(422);
        assertThat(body(tooMuch).get("code").asText()).isEqualTo("payment_too_high");

        paid(total - 10_000, "VIREMENT", "VIR-2");
        var settled = invoice();
        assertThat(settled.at("/summary/status").asText()).isEqualTo("PAYEE");
        assertThat(settled.at("/summary/paid").asLong()).isEqualTo(total);
        assertThat(settled.get("payments")).hasSize(2);
        assertThat(settled.get("events")).extracting(e -> e.get("kind").asText())
                .containsExactly("GENEREE", "PAIEMENT", "PAIEMENT");
        assertThat(status(pay(1, "CARTE", "T-1"))).isEqualTo(422);
        assertThat(auditCount("PAYMENT_RECORDED")).isEqualTo(2);
    }

    @Test
    void especesAvecRecuNumerote() throws Exception {
        var first = paid(5_000, "ESPECES", null);
        assertThat(first.get("receiptNumber").asText()).isEqualTo(number("R", 1));
        assertThat(paid(5_000, "ESPECES", null).get("receiptNumber").asText()).isEqualTo(number("R", 2));
        assertThat(pdf("/api/v1/billing/payments/" + first.get("id").asText() + "/receipt")).startsWith("%PDF");

        var transfer = paid(1_000, "VIREMENT", null);
        assertThat(transfer.get("receiptNumber").isNull()).isTrue();
        var noReceipt = getAs(director, "/api/v1/billing/payments/" + transfer.get("id").asText() + "/receipt");
        assertThat(status(noReceipt)).isEqualTo(422);
    }

    @Test
    void annulerUnPaiementRendLeResteDu() throws Exception {
        var payment = paid(total, "CARTE", "TPE-778");
        assertThat(invoice().at("/summary/status").asText()).isEqualTo("PAYEE");
        var path = "/api/v1/billing/payments/" + payment.get("id").asText() + "/cancel";
        assertThat(status(perform(post(path), director, Map.of("reason", " ")))).isEqualTo(400);
        var cancelled = body(perform(post(path), director, Map.of("reason", "Paiement rejeté par la banque")));
        assertThat(cancelled.get("cancelled").asBoolean()).isTrue();
        assertThat(invoice().at("/summary/remaining").asLong()).isEqualTo(total);
        assertThat(invoice().get("events")).extracting(e -> e.get("kind").asText()).contains("PAIEMENT_ANNULE");
        assertThat(status(perform(post(path), director, Map.of("reason", "Encore")))).isEqualTo(409);

        var full = perform(post("/api/v1/billing/invoices/" + invoiceId + "/credit-notes"), director,
                Map.of("reason", "Erreur", "full", true));
        assertThat(status(full)).as("plus aucun paiement lettré").isEqualTo(201);
    }

    @Test
    void avoirTotalRefuseTantQuUnPaiementEstLettre() throws Exception {
        paid(1_000, "VIREMENT", null);
        var refused = perform(post("/api/v1/billing/invoices/" + invoiceId + "/credit-notes"), director,
                Map.of("reason", "Erreur", "full", true));
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).get("code").asText()).isEqualTo("invoice_paid");
    }

    @Test
    void relancesEtImpayesEchus() throws Exception {
        var early = perform(post("/api/v1/billing/invoices/" + invoiceId + "/reminders"), director, null);
        assertThat(status(early)).isEqualTo(422);
        assertThat(body(early).get("code").asText()).isEqualTo("reminder_not_due");

        overdueBy(35);
        var collections = body(getAs(director, "/api/v1/billing/collections"));
        assertThat(collections.get("overdueCount").asInt()).isEqualTo(1);
        assertThat(collections.get("overdue").asLong()).isEqualTo(total);
        assertThat(collections.get("overdueOver30").asInt()).isEqualTo(1);
        assertThat(collections.get("upcomingCount").asInt()).as("la facture de la famille Poédi").isEqualTo(1);
        assertThat(invoice().at("/summary/status").asText()).isEqualTo("EN_RETARD");
        assertThat(invoice().at("/summary/daysLate").asLong()).isEqualTo(35);

        String last = null;
        for (int level = 1; level <= 3; level++) {
            var reminder = body(perform(post("/api/v1/billing/invoices/" + invoiceId + "/reminders"), director, null));
            assertThat(reminder.get("level").asInt()).isEqualTo(level);
            last = reminder.get("id").asText();
        }
        var fourth = perform(post("/api/v1/billing/invoices/" + invoiceId + "/reminders"), director, null);
        assertThat(body(fourth).get("code").asText()).isEqualTo("reminder_max");
        assertThat(pdf("/api/v1/billing/reminders/" + last + "/pdf")).startsWith("%PDF");
        var sent = body(perform(post("/api/v1/billing/reminders/" + last + "/sent"), director,
                Map.of("sentOn", TODAY.toString())));
        assertThat(sent.get("sentOn").asText()).isEqualTo(TODAY.toString());
        assertThat(invoice().at("/summary/reminderLevel").asInt()).isEqualTo(3);

        paid(total, "VIREMENT", null);
        assertThat(body(getAs(director, "/api/v1/billing/collections")).get("overdueCount").asInt()).isZero();
    }

    @Test
    void reglementDuFinanceur() throws Exception {
        var cafat = funder("CAFAT", "MONTANT_FIXE", 12_000, 0, null, 0);
        grant(tom, cafat, "Sarah Poédi");
        // L'aide est accordée après la facture : avoir total, puis nouvelle facture avec l'aide.
        perform(post("/api/v1/billing/invoices/" + invoiceOf("Famille Poédi") + "/credit-notes"), director,
                Map.of("reason", "Aide CAFAT accordée", "full", true));
        generated();
        var request = body(getAs(director, "/api/v1/billing/funder-requests?month=" + MONTH)).get(0);
        assertThat(request.get("total").asLong()).isEqualTo(12_000);
        var payment = new LinkedHashMap<String, Object>();
        payment.put("funderRequestId", request.get("id").asText());
        payment.put("paidOn", TODAY.toString());
        payment.put("amount", 12_000);
        payment.put("method", "VIREMENT");
        payment.put("reference", "CAFAT-2026-11");
        var result = perform(post("/api/v1/billing/payments"), director, payment);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(result).get("payerName").asText()).isEqualTo("CAFAT");
        var settled = body(getAs(director, "/api/v1/billing/funder-requests/" + request.get("id").asText()));
        assertThat(settled.get("status").asText()).isEqualTo("REGLEE");
        assertThat(settled.get("received").asLong()).isEqualTo(12_000);
        payment.put("amount", 1);
        assertThat(status(perform(post("/api/v1/billing/payments"), director, payment))).isEqualTo(422);
    }

    @Test
    void attestationAnnuelle() throws Exception {
        paid(total, "VIREMENT", null);
        var family = jdbc.queryForObject("SELECT family_id::text FROM child WHERE id = ?::uuid", String.class, lea);
        assertThat(pdf("/api/v1/billing/families/" + family + "/attestation?year=" + MONTH.getYear())).startsWith("%PDF");
        assertThat(auditCount("ATTESTATION_ISSUED")).isEqualTo(1);
        var empty = getAs(director, "/api/v1/billing/families/" + family + "/attestation?year=2000");
        assertThat(status(empty)).isEqualTo(422);
    }

    String pdf(String path) throws Exception {
        var result = getAs(director, path);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return new String(result.getResponse().getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1);
    }
}
