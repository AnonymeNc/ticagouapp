package nc.ticagou.portal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import nc.ticagou.child.Child;
import nc.ticagou.child.Consent;
import nc.ticagou.common.Formats;
import nc.ticagou.structure.Structure;

/** Autorisation parentale à signer sur le portail (photos, sorties, soins d'urgence). */
@Component
public class ConsentPdf {

    private final ITemplateEngine templates;
    private final Clock clock;

    public ConsentPdf(ITemplateEngine templates, Clock clock) {
        this.templates = templates;
        this.clock = clock;
    }

    public static String title(Consent.Type type) {
        return switch (type) {
            case PHOTOS -> "Autorisation de photographier";
            case SORTIES -> "Autorisation de sorties";
            case SOINS_URGENCE -> "Autorisation de soins d’urgence";
        };
    }

    static List<String> lines(Consent.Type type, String structureName) {
        return switch (type) {
            case PHOTOS -> List.of(
                    "J’autorise %s à photographier mon enfant pendant les activités de la journée.".formatted(structureName),
                    "Les photos sont partagées uniquement avec les familles des enfants présents sur la photo, sur le portail parents, et conservées 60 jours au plus. Elles ne sont jamais publiées ailleurs.");
            case SORTIES -> List.of(
                    "J’autorise %s à emmener mon enfant en sortie (promenade, parc, bibliothèque), accompagné par l’équipe, dans le respect du taux d’encadrement.".formatted(structureName));
            case SOINS_URGENCE -> List.of(
                    "J’autorise l’équipe de %s à prendre, en cas d’urgence, toutes les mesures nécessaires : appel du SAMU (15), transport vers l’hôpital, soins prescrits par un médecin.".formatted(structureName),
                    "L’équipe me prévient sans délai.");
        };
    }

    public byte[] render(Structure structure, Child child, Consent.Type type, String proposedBy) {
        var vars = new HashMap<String, Object>();
        vars.put("title", title(type));
        vars.put("structureLine", structure.getName() + (structure.getCity() == null ? "" : " · " + structure.getCity()));
        vars.put("childLine", "Pour %s %s, né(e) le %s".formatted(child.getFirstName(), child.getLastName(),
                Formats.date(child.getBirthDate())));
        vars.put("lines", lines(type, structure.getName()));
        vars.put("proposedLine", "Proposée le %s par %s, à signer sur le portail parents."
                .formatted(Formats.date(LocalDate.now(clock)), proposedBy));
        vars.put("footer", title(type) + " · " + child.getFirstName() + " " + child.getLastName());
        var html = templates.process("autorisation", new Context(Locale.FRANCE, vars));
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
}
