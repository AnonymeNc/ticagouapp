package nc.ticagou.console.billing;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import nc.ticagou.console.ConsoleProperties;

/**
 * Envoi SMTP d'un message de la boîte d'envoi, toujours à la demande de l'éditeur (jamais en arrière-plan) : la réponse
 * dit si le serveur de messagerie l'a accepté.
 */
@Component
public class Mailer {

    public static final class MailFailure extends RuntimeException {
        MailFailure(String message) {
            super(message);
        }
    }

    private final JavaMailSender sender;
    private final ConsoleProperties properties;

    public Mailer(JavaMailSender sender, ConsoleProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    public record Attachment(String name, byte[] bytes) {
    }

    public void send(String to, String cc, String subject, String body, String attachmentName, byte[] attachment) {
        send(to, cc, subject, body, attachment == null ? List.of() : List.of(new Attachment(attachmentName, attachment)));
    }

    public void send(String to, String cc, String subject, String body, List<Attachment> attachments) {
        var from = properties.mailFrom();
        if (from == null || from.isBlank()) {
            throw new MailFailure("Adresse d’expédition absente (CONSOLE_MAIL_FROM).");
        }
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, !attachments.isEmpty(), StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(addresses(to, "destinataire"));
            if (cc != null && !cc.isBlank()) {
                helper.setCc(addresses(cc, "copie"));
            }
            helper.setSubject(subject);
            helper.setText(body, false);
            for (var a : attachments) {
                helper.addAttachment(a.name(), new ByteArrayResource(a.bytes()), "application/pdf");
            }
            sender.send(message);
        } catch (MessagingException | MailException e) {
            throw new MailFailure("Le serveur de messagerie a refusé l’envoi : " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " — " + e.getMessage().lines().findFirst().orElse("")));
        }
    }

    /** Adresses séparées par des virgules ou des points-virgules, vérifiées. */
    static InternetAddress[] addresses(String list, String what) {
        var parts = list == null ? List.<String>of()
                : Arrays.stream(list.split("[,;]")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (parts.isEmpty()) {
            throw new MailFailure("Aucune adresse de " + what + ".");
        }
        var result = new InternetAddress[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            try {
                result[i] = new InternetAddress(parts.get(i), true);
            } catch (AddressException e) {
                throw new MailFailure("Adresse de %s invalide : %s".formatted(what, parts.get(i)));
            }
        }
        return result;
    }
}
