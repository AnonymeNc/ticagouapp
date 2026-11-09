package nc.ticagou.console.billing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.fasterxml.jackson.databind.JsonNode;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import nc.ticagou.console.billing.InvoiceRepository.Line;

/**
 * PDF d'une facture ou d'un avoir (Thymeleaf + openhtmltopdf, ADR 0004), produit une seule fois, à la validation, avec
 * les mentions recopiées dans la facture : il est stocké avec son empreinte SHA-256 et ne change plus.
 */
@Component
public class InvoicePdf {

    /** Ce que le gabarit affiche ; tout est déjà mis en forme (montants, dates). */
    public record Document(String title, String number, boolean credit, String issuedOn, String dueOn, String subject, String period,
                           String corrects, JsonNode issuer, JsonNode client, List<Line> lines, Totals.Result totals, String note) {
    }

    private final TemplateEngine templates;

    public InvoicePdf(TemplateEngine templates) {
        this.templates = templates;
    }

    public byte[] render(Document d) {
        var vars = new HashMap<String, Object>();
        vars.put("title", d.title());
        vars.put("number", d.number());
        vars.put("credit", d.credit());
        vars.put("issuedOn", d.issuedOn());
        vars.put("dueOn", d.credit() ? null : d.dueOn());
        vars.put("subject", d.subject());
        vars.put("period", d.period());
        vars.put("corrects", d.corrects());
        vars.put("issuer", Map.of("name", text(d.issuer(), "name"), "paymentTerms", text(d.issuer(), "paymentTerms"),
                "latePenalties", text(d.issuer(), "latePenalties")));
        vars.put("issuerLines", nonBlank(Stream.of(join(" · ", text(d.issuer(), "legalForm"), "RIDET " + text(d.issuer(), "ridet")),
                text(d.issuer(), "address"), join(" · ", text(d.issuer(), "phone"), text(d.issuer(), "email")),
                text(d.issuer(), "tgcNumber").isBlank() ? "" : "N° TGC " + text(d.issuer(), "tgcNumber"))));
        vars.put("client", Map.of("name", text(d.client(), "name")));
        vars.put("clientLines", nonBlank(Stream.of(text(d.client(), "crecheName").equals(text(d.client(), "name")) ? ""
                : text(d.client(), "crecheName"), text(d.client(), "address"),
                text(d.client(), "ridet").isBlank() ? "" : "RIDET " + text(d.client(), "ridet"))));
        var rows = new ArrayList<Map<String, Object>>();
        for (var l : d.lines()) {
            var row = new HashMap<String, Object>();
            row.put("label", l.label());
            row.put("detail", l.detail());
            row.put("quantity", l.quantity());
            row.put("unitPrice", Xpf.format(l.unitPrice()));
            row.put("amount", Xpf.format(l.amount()));
            row.put("rate", Xpf.rate(l.tgcRate()));
            rows.add(row);
        }
        vars.put("lines", rows);
        vars.put("totalHt", Xpf.format(d.totals().ht()));
        vars.put("totalTtc", Xpf.format(d.totals().ttc()));
        vars.put("rates", d.totals().rates().stream().map(r -> Map.of("label", "TGC %s sur %s".formatted(Xpf.rate(r.rate()), Xpf.format(r.base())),
                "amount", Xpf.format(r.tgc()))).toList());
        vars.put("dueLine", d.credit() || d.dueOn() == null ? null : "À régler avant le " + d.dueOn());
        vars.put("note", d.note());
        var bank = join(" · ", text(d.issuer(), "bank"), text(d.issuer(), "iban").isBlank() ? "" : "IBAN " + text(d.issuer(), "iban"));
        vars.put("paymentLine", d.credit() || bank.isBlank() ? null : "Virement : %s · référence %s".formatted(bank, d.number()));
        vars.put("footer", join(" · ", text(d.issuer(), "name"), text(d.issuer(), "footer"), d.title() + " " + d.number()));
        var html = templates.process("facture", new Context(Locale.FRANCE, vars));
        var out = new ByteArrayOutputStream();
        try {
            var builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? "" : node.get(field).asText();
    }

    private static String join(String separator, String... parts) {
        return String.join(separator, Stream.of(parts).filter(p -> p != null && !p.isBlank() && !p.equals("RIDET ")).toList());
    }

    private static List<String> nonBlank(Stream<String> lines) {
        return lines.filter(l -> l != null && !l.isBlank()).toList();
    }
}
