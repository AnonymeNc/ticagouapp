package nc.ticagou.portal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.document.Uploads;
import nc.ticagou.portal.PortalDtos.BroadcastResult;
import nc.ticagou.portal.PortalDtos.NewsDto;
import nc.ticagou.structure.Section;
import nc.ticagou.structure.SectionRepository;

/**
 * Actualités de la crèche et messages à plusieurs familles (lot 1 de l'application parents). Une actualité part au
 * portail comme un élément NEWS, pour les familles invitées de toute la crèche ou d'une section ; un message à
 * plusieurs familles est un message dans le fil de chacune.
 */
@Service
public class NewsService {

    private static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    /** Pièce jointe : la file d'envoi transporte le fichier en Base64 dans des lots de 8 Mo au plus. */
    static final long MAX_ATTACHMENT_BYTES = 5L * 1024 * 1024;
    /** Une actualité sans date de fin reste au plus 12 mois sur le portail ; au-delà, la date est refusée. */
    private static final int MAX_MONTHS = 12;

    private final StructureNewsRepository news;
    private final PortalSettingsRepository settings;
    private final PortalProjections projections;
    private final PortalPublisher publisher;
    private final FamilyMessageRepository messages;
    private final SectionRepository sections;
    private final EncryptedFileStore fileStore;
    private final StoredFileRepository files;
    private final AuditService audit;
    private final Clock clock;

    public NewsService(StructureNewsRepository news, PortalSettingsRepository settings, PortalProjections projections,
                       PortalPublisher publisher, FamilyMessageRepository messages, SectionRepository sections,
                       EncryptedFileStore fileStore, StoredFileRepository files, AuditService audit, Clock clock) {
        this.news = news;
        this.settings = settings;
        this.projections = projections;
        this.publisher = publisher;
        this.messages = messages;
        this.sections = sections;
        this.fileStore = fileStore;
        this.files = files;
        this.audit = audit;
        this.clock = clock;
    }

    /** Pièce jointe reçue : contenu et nom d'origine. */
    public record Attachment(byte[] content, String originalName) {
    }

    @Transactional(readOnly = true)
    public List<NewsDto> list() {
        var structureId = CurrentUser.get().structureId();
        return news.findByStructureIdOrderByPublishedAtDesc(structureId).stream().map(this::dto).toList();
    }

    @Transactional
    public NewsDto publish(String title, String body, UUID sectionId, LocalDate expiresOn, Attachment attachment) {
        var user = CurrentUser.get();
        requireActive(user.structureId());
        var cleanTitle = required(title, 120, "news_title", "Donnez un titre à l’actualité (120 caractères au plus).");
        var cleanBody = required(body, 2000, "news_body", "Écrivez le texte de l’actualité (2 000 caractères au plus).");
        if (sectionId != null) {
            section(sectionId);
        }
        var today = today();
        if (expiresOn != null && (expiresOn.isBefore(today) || expiresOn.isAfter(today.plusMonths(MAX_MONTHS)))) {
            throw ApiException.rule("news_expiry", "La date de fin doit être comprise entre aujourd’hui et dans 12 mois.");
        }
        UUID fileId = null;
        String fileName = null;
        if (attachment != null && attachment.content() != null && attachment.content().length > 0) {
            if (attachment.content().length > MAX_ATTACHMENT_BYTES) {
                throw ApiException.rule("news_file_size", "La pièce jointe doit peser au plus 5 Mo.");
            }
            var type = Uploads.checkDocument(attachment.content());
            fileId = fileStore.store(user.structureId(), attachment.content(), type).getId();
            fileName = Uploads.safeFileName(attachment.originalName(), "piece-jointe." + Uploads.extension(type));
        }
        var saved = news.save(new StructureNews(UUID.randomUUID(), user.structureId(), sectionId, cleanTitle, cleanBody,
                fileId, fileName, user.displayName(), clock.instant(), expiresOn));
        publisher.on(new PortalChange(user.structureId(), PortalItemType.NEWS, saved.getId()));
        audit.record(AuditAction.PORTAL_NEWS_PUBLISHED, "news", saved.getId(),
                Map.of("families", projections.newsFamilies(saved).size()));
        return dto(saved);
    }

    @Transactional
    public NewsDto withdraw(UUID id) {
        var user = CurrentUser.get();
        var n = find(id);
        if (n.getWithdrawnAt() == null) {
            n.withdraw(user.displayName(), clock.instant());
            publisher.on(new PortalChange(user.structureId(), PortalItemType.NEWS, id));
            audit.record(AuditAction.PORTAL_NEWS_WITHDRAWN, "news", id, Map.of());
        }
        return dto(n);
    }

    /** Pièce jointe, pour la relire au bureau avant ou après publication. */
    @Transactional(readOnly = true)
    public PortalDtos.FileContent file(UUID id) {
        var n = find(id);
        if (n.getFileId() == null) {
            throw ApiException.notFound("Pas de pièce jointe.");
        }
        var stored = files.findById(n.getFileId()).orElseThrow(() -> ApiException.notFound("Pièce jointe introuvable."));
        return new PortalDtos.FileContent(fileStore.read(stored), stored.getContentType(), n.getFileName());
    }

    /** Message à toutes les familles invitées, ou à celles d'une section : un message dans le fil de chacune. */
    @Transactional
    public BroadcastResult broadcast(UUID sectionId, String body) {
        var user = CurrentUser.get();
        requireActive(user.structureId());
        var text = required(body, 2000, "message_body", "Écrivez votre message (2 000 caractères au plus).");
        if (sectionId != null) {
            section(sectionId);
        }
        var families = projections.invitedFamilies(user.structureId(), sectionId);
        if (families.isEmpty()) {
            throw ApiException.rule("no_family", sectionId == null
                    ? "Aucune famille n’est invitée au portail."
                    : "Aucune famille de cette section n’est invitée au portail.");
        }
        var now = clock.instant();
        for (var familyId : families) {
            var message = messages.save(FamilyMessage.fromTeam(user.structureId(), familyId, user.userId(),
                    user.displayName(), text, now));
            publisher.on(new PortalChange(user.structureId(), PortalItemType.MESSAGE, message.getId()));
        }
        audit.record(AuditAction.FAMILY_MESSAGE_BROADCAST, "section", sectionId == null ? user.structureId() : sectionId,
                Map.of("families", families.size()));
        return new BroadcastResult(families.size());
    }

    // --- Outils -------------------------------------------------------------------------------

    private NewsDto dto(StructureNews n) {
        var today = today();
        var status = n.getWithdrawnAt() != null ? "RETIREE" : n.isVisibleOn(today) ? "PUBLIEE" : "EXPIREE";
        var sectionName = n.getSectionId() == null ? null
                : sections.findById(n.getSectionId()).map(Section::getName).orElse(null);
        return new NewsDto(n.getId(), n.getSectionId(), sectionName, n.getTitle(), n.getBody(), n.getFileName(),
                n.getPublishedBy(), n.getPublishedAt(), n.getExpiresOn(), n.getWithdrawnAt(), status,
                "PUBLIEE".equals(status) ? projections.newsFamilies(n).size() : 0);
    }

    private StructureNews find(UUID id) {
        return news.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Actualité introuvable."));
    }

    private Section section(UUID sectionId) {
        return sections.findByIdAndStructureId(sectionId, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Section introuvable."));
    }

    private void requireActive(UUID structureId) {
        if (settings.findById(structureId).filter(PortalSettings::isActive).isEmpty()) {
            throw ApiException.rule("portal_inactive", "Activez d’abord le portail parents (Paramètres › Portail parents).");
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(NOUMEA));
    }

    private static String required(String value, int max, String code, String message) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw ApiException.rule(code, message);
        }
        return value.strip();
    }
}
