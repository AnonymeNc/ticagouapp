package nc.ticagou.portail.auth;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import nc.ticagou.portail.PortalProperties;

/**
 * Envoi du lien de connexion. L'envoi se fait en arrière-plan : la réponse à la demande ne dépend ni de l'existence
 * de l'adresse ni de la lenteur du serveur SMTP. Ni le lien ni le code ne sont écrits dans les journaux.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final JavaMailSender sender;
    private final PortalProperties properties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public Mailer(JavaMailSender sender, PortalProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    public void sendLogin(String email, String token, String code) {
        var link = trim(properties.publicUrl()) + "/connexion#jeton=" + token;
        var message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("Votre connexion à Ti'Cagou");
        message.setText("""
                Bonjour,

                Pour ouvrir l'application de la crèche, touchez ce lien :
                %s

                Ou saisissez ce code dans l'application : %s %s

                Le lien et le code sont valables 15 minutes et ne servent qu'une fois.
                Si vous n'avez rien demandé, ignorez ce message : personne ne peut se connecter sans lui.

                L'équipe Ti'Cagou
                """.formatted(link, code.substring(0, 3), code.substring(3)));
        executor.submit(() -> {
            try {
                sender.send(message);
            } catch (MailException e) {
                log.warn("Envoi du lien de connexion impossible : {}", e.getClass().getSimpleName());
            }
        });
    }

    /** Message du service (supervision) : texte simple, envoyé en arrière-plan. */
    public void send(String to, String subject, String text) {
        var message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        executor.submit(() -> {
            try {
                sender.send(message);
            } catch (MailException e) {
                log.warn("Envoi du message « {} » impossible : {}", subject, e.getClass().getSimpleName());
            }
        });
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
