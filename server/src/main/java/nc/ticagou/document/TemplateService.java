package nc.ticagou.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.AppUserRepository;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;
import nc.ticagou.contract.ContractRepository;
import nc.ticagou.family.Guardian;
import nc.ticagou.family.GuardianRepository;
import nc.ticagou.staff.StaffProfileRepository;
import nc.ticagou.structure.SectionRepository;
import nc.ticagou.structure.StructureRepository;

/**
 * Modèles de documents (lot 6) : attestations, certificats, courriers aux familles, contrat de travail. Texte libre
 * avec des champs de fusion ({@code {{enfant.prenom}}}) vérifiés à l'enregistrement ; chaque modification crée une
 * version datée. Le PDF passe par le moteur des contrats (ADR 0004) : logo, signataire et image de signature et
 * cachet de la structure. La signature électronique n'est pas couverte.
 */
@Service
public class TemplateService {

    public enum Subject { ENFANT, PERSONNEL }

    public record Field(String key, String label) {
    }

    public record TemplateDto(UUID id, Subject subject, String title, String body, int version, LocalDate validFrom,
                              String author, boolean retired) {
    }

    public record Settings(String signatory, boolean hasStamp) {
    }

    public record Pdf(byte[] content, String fileName) {
    }

    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([a-z_]+\\.[a-z_]+)\\s*}}");
    private static final int MAX_BODY = 20_000;

    static final List<Field> COMMON = List.of(new Field("structure.nom", "Nom de la structure"),
            new Field("structure.adresse", "Adresse"), new Field("structure.ville", "Commune"),
            new Field("structure.ridet", "RIDET"), new Field("structure.agrement", "Numéro d'agrément"),
            new Field("structure.telephone", "Téléphone"), new Field("date.jour", "Date du jour"),
            new Field("signataire.nom", "Signataire"));
    static final List<Field> CHILD = List.of(new Field("enfant.prenom", "Prénom de l'enfant"),
            new Field("enfant.nom", "Nom de l'enfant"), new Field("enfant.naissance", "Date de naissance"),
            new Field("enfant.section", "Section"), new Field("enfant.entree", "Date d'entrée"),
            new Field("famille.responsables", "Responsables légaux"), new Field("contrat.numero", "Numéro du contrat"),
            new Field("contrat.debut", "Début du contrat"), new Field("contrat.fin", "Fin du contrat"));
    static final List<Field> STAFF = List.of(new Field("salarie.prenom", "Prénom"), new Field("salarie.nom", "Nom"),
            new Field("salarie.fonction", "Fonction"), new Field("salarie.diplome", "Diplôme"),
            new Field("salarie.embauche", "Date d'embauche"), new Field("salarie.contrat", "Type de contrat"),
            new Field("salarie.heures_semaine", "Durée hebdomadaire"));

    private final JdbcTemplate jdbc;
    private final ITemplateEngine engine;
    private final StructureRepository structures;
    private final SectionRepository sections;
    private final ChildRepository children;
    private final GuardianRepository guardians;
    private final ContractRepository contracts;
    private final AppUserRepository users;
    private final StaffProfileRepository profiles;
    private final EncryptedFileStore fileStore;
    private final StoredFileRepository files;
    private final AuditService audit;
    private final Clock clock;

    public TemplateService(JdbcTemplate jdbc, ITemplateEngine engine, StructureRepository structures, SectionRepository sections,
                           ChildRepository children, GuardianRepository guardians, ContractRepository contracts,
                           AppUserRepository users, StaffProfileRepository profiles, EncryptedFileStore fileStore,
                           StoredFileRepository files, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.structures = structures;
        this.sections = sections;
        this.children = children;
        this.guardians = guardians;
        this.contracts = contracts;
        this.users = users;
        this.profiles = profiles;
        this.fileStore = fileStore;
        this.files = files;
        this.audit = audit;
        this.clock = clock;
    }

    public static List<Field> fields(Subject subject) {
        var all = new ArrayList<>(COMMON);
        all.addAll(subject == Subject.ENFANT ? CHILD : STAFF);
        return all;
    }

    // --- Modèles -------------------------------------------------------------------------------

    public List<TemplateDto> list() {
        return jdbc.query(SELECT + "WHERE t.structure_id = ? ORDER BY t.retired_at NULLS FIRST, v.title",
                (rs, i) -> dto(rs), CurrentUser.get().structureId());
    }

    public List<TemplateDto> versions(UUID id) {
        load(id);
        return jdbc.query("SELECT t.id, t.subject, v.title, v.body, v.version, v.valid_from, v.author, t.retired_at "
                + "FROM document_template t JOIN document_template_version v ON v.template_id = t.id WHERE t.id = ? "
                + "ORDER BY v.version DESC", (rs, i) -> dto(rs), id);
    }

    @Transactional
    public TemplateDto create(Subject subject, String title, String body) {
        var user = CurrentUser.get();
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO document_template (id, structure_id, subject, created_at) VALUES (?, ?, ?, ?)",
                id, user.structureId(), subject.name(), Timestamp.from(clock.instant()));
        addVersion(id, subject, title, body, 1);
        return load(id);
    }

    /** Nouvelle version, en vigueur dès aujourd'hui ; les précédentes restent consultables. */
    @Transactional
    public TemplateDto update(UUID id, String title, String body) {
        var current = load(id);
        if (current.retired()) {
            throw ApiException.rule("template_retired", "Ce modèle est retiré.");
        }
        addVersion(id, current.subject(), title, body, current.version() + 1);
        return load(id);
    }

    @Transactional
    public void retire(UUID id) {
        load(id);
        jdbc.update("UPDATE document_template SET retired_at = ? WHERE id = ?", Timestamp.from(clock.instant()), id);
        audit.record(AuditAction.DOCUMENT_TEMPLATE_CHANGED, "document_template", id, Map.of("action", "retired"));
    }

    /** Modèles de départ, à adapter : seulement si la crèche n'en a encore aucun. */
    @Transactional
    public List<TemplateDto> starters() {
        if (!list().isEmpty()) {
            throw ApiException.rule("templates_exist", "Des modèles existent déjà.");
        }
        create(Subject.ENFANT, "Attestation de présence", """
                # Attestation de présence

                Je soussigné(e), {{signataire.nom}}, direction de {{structure.nom}}, atteste que l'enfant {{enfant.prenom}} {{enfant.nom}}, né(e) le {{enfant.naissance}}, est accueilli(e) dans notre structure depuis le {{enfant.entree}}, section {{enfant.section}}.

                Attestation établie à la demande de {{famille.responsables}}, pour servir et valoir ce que de droit.

                Fait à {{structure.ville}}, le {{date.jour}}.""");
        create(Subject.ENFANT, "Certificat d'inscription", """
                # Certificat d'inscription

                {{structure.nom}} (agrément n° {{structure.agrement}}) certifie que {{enfant.prenom}} {{enfant.nom}} est inscrit(e) sous le contrat n° {{contrat.numero}}, à compter du {{contrat.debut}}.

                Fait à {{structure.ville}}, le {{date.jour}}.""");
        create(Subject.PERSONNEL, "Attestation employeur", """
                # Attestation employeur

                Je soussigné(e), {{signataire.nom}}, direction de {{structure.nom}} (RIDET {{structure.ridet}}), atteste que {{salarie.prenom}} {{salarie.nom}} est employé(e) en qualité de {{salarie.fonction}} depuis le {{salarie.embauche}}, en {{salarie.contrat}}, pour {{salarie.heures_semaine}} par semaine.

                Fait à {{structure.ville}}, le {{date.jour}}.""");
        return list();
    }

    private void addVersion(UUID id, Subject subject, String title, String body, int version) {
        var cleanTitle = title == null ? "" : title.strip();
        if (cleanTitle.isEmpty() || cleanTitle.length() > 120) {
            throw ApiException.rule("template_title", "Donnez un titre au modèle (120 caractères au plus).");
        }
        var text = body == null ? "" : body.strip();
        if (text.isEmpty() || text.length() > MAX_BODY) {
            throw ApiException.rule("template_body", "Le texte du modèle est vide ou trop long (20 000 caractères au plus).");
        }
        var known = fields(subject).stream().map(Field::key).collect(java.util.stream.Collectors.toSet());
        var unknown = new java.util.TreeSet<String>();
        var matcher = FIELD.matcher(text);
        while (matcher.find()) {
            if (!known.contains(matcher.group(1))) {
                unknown.add(matcher.group(1));
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.rule("unknown_field", "Champ inconnu : " + String.join(", ", unknown) + ".");
        }
        jdbc.update("INSERT INTO document_template_version (template_id, version, title, body, valid_from, author, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, version, cleanTitle, text, Date.valueOf(LocalDate.now(clock)),
                CurrentUser.get().displayName(), Timestamp.from(clock.instant()));
        audit.record(AuditAction.DOCUMENT_TEMPLATE_CHANGED, "document_template", id, Map.of("version", version));
    }

    // --- Documents produits -------------------------------------------------------------------

    /** Document pour un enfant ou un membre du personnel, avec la version en vigueur du modèle (tracé dans l'audit). */
    @Transactional
    public Pdf render(UUID templateId, UUID subjectId) {
        var template = load(templateId);
        if (template.retired()) {
            throw ApiException.rule("template_retired", "Ce modèle est retiré.");
        }
        var values = values(template.subject(), subjectId);
        var blocks = new ArrayList<Map<String, Object>>();
        for (var paragraph : template.body().split("\\R\\s*\\R")) {
            var text = merge(paragraph.strip(), values);
            if (text.isEmpty()) {
                continue;
            }
            boolean heading = text.startsWith("# ");
            blocks.add(Map.of("heading", heading, "lines", List.of((heading ? text.substring(2) : text).split("\\R"))));
        }
        var structure = structures.findById(CurrentUser.get().structureId()).orElseThrow();
        var vars = new HashMap<String, Object>();
        vars.put("title", template.title());
        vars.put("structureName", structure.getName());
        vars.put("structureLine", line(structure.getAddress(), structure.getCity(), structure.getPhone()));
        vars.put("logo", structure.getLogo() == null ? null : "data:%s;base64,%s".formatted(structure.getLogoContentType(),
                Base64.getEncoder().encodeToString(structure.getLogo())));
        vars.put("blocks", blocks);
        vars.put("signatory", values.get("signataire.nom"));
        vars.put("stamp", stampDataUri());
        vars.put("footer", "%s · %s · version %d".formatted(structure.getName(), template.title(), template.version()));
        var html = engine.process("modele", new Context(Locale.FRANCE, vars));
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
        audit.record(AuditAction.DOCUMENT_GENERATED, template.subject() == Subject.ENFANT ? "child" : "user", subjectId,
                Map.of("template", template.title(), "version", template.version()));
        var name = template.title().toLowerCase(Locale.FRANCE).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return new Pdf(out.toByteArray(), (name.isEmpty() ? "document" : name) + ".pdf");
    }

    Map<String, String> values(Subject subject, UUID subjectId) {
        var user = CurrentUser.get();
        var structure = structures.findById(user.structureId()).orElseThrow();
        var settings = settings();
        var v = new LinkedHashMap<String, String>();
        v.put("structure.nom", structure.getName());
        v.put("structure.adresse", line(structure.getAddress(), structure.getPostalCode(), structure.getCity()));
        v.put("structure.ville", structure.getCity());
        v.put("structure.ridet", structure.getRidet());
        v.put("structure.agrement", structure.getAgrementNumber());
        v.put("structure.telephone", structure.getPhone());
        v.put("date.jour", Formats.date(LocalDate.now(clock)));
        v.put("signataire.nom", settings.signatory() != null ? settings.signatory() : user.displayName());
        if (subject == Subject.ENFANT) {
            var child = children.findByIdAndStructureId(subjectId, user.structureId())
                    .orElseThrow(() -> ApiException.notFound("Enfant introuvable."));
            v.put("enfant.prenom", child.getFirstName());
            v.put("enfant.nom", child.getLastName());
            v.put("enfant.naissance", child.getBirthDate() == null ? null : Formats.date(child.getBirthDate()));
            v.put("enfant.section", child.getSectionId() == null ? null
                    : sections.findById(child.getSectionId()).map(s -> s.getName()).orElse(null));
            v.put("enfant.entree", child.getEntryDate() == null ? null : Formats.date(child.getEntryDate()));
            v.put("famille.responsables", String.join(" et ", guardians.findByFamilyIdOrderByDisplayOrderAscLastNameAsc(child.getFamilyId())
                    .stream().filter(Guardian::isLegalGuardian).map(g -> g.getFirstName() + " " + g.getLastName()).toList()));
            var contract = contracts.findByChildIdOrderByStartDateDesc(child.getId()).stream().findFirst();
            v.put("contrat.numero", contract.map(c -> c.getNumber()).orElse(null));
            v.put("contrat.debut", contract.map(c -> Formats.date(c.getStartDate())).orElse(null));
            v.put("contrat.fin", contract.map(c -> c.getEndDate() == null ? null : Formats.date(c.getEndDate())).orElse(null));
        } else {
            var staff = users.findById(subjectId).filter(u -> u.getStructureId().equals(user.structureId()))
                    .orElseThrow(() -> ApiException.notFound("Membre du personnel introuvable."));
            var profile = profiles.findById(subjectId);
            v.put("salarie.prenom", staff.getFirstName());
            v.put("salarie.nom", staff.getLastName());
            v.put("salarie.fonction", staff.getJobTitle());
            v.put("salarie.diplome", profile.map(p -> p.getDiploma()).orElse(null));
            v.put("salarie.embauche", profile.map(p -> p.getHiredOn() == null ? null : Formats.date(p.getHiredOn())).orElse(null));
            v.put("salarie.contrat", profile.map(p -> p.getContractType() == null ? null : p.getContractType().name()).orElse(null));
            v.put("salarie.heures_semaine", profile.map(p -> p.getWeeklyMinutes() == null ? null
                    : Formats.duration(p.getWeeklyMinutes())).orElse(null));
        }
        return v;
    }

    /** Remplace les champs ; une valeur absente laisse une ligne à compléter à la main. */
    static String merge(String text, Map<String, String> values) {
        var matcher = FIELD.matcher(text);
        var out = new StringBuilder();
        while (matcher.find()) {
            var value = values.get(matcher.group(1));
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value == null || value.isBlank() ? "__________" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // --- Signataire et cachet -----------------------------------------------------------------

    public Settings settings() {
        return jdbc.query("SELECT signatory, stamp_file_id FROM document_settings WHERE structure_id = ?",
                (rs, i) -> new Settings(rs.getString(1), rs.getObject(2) != null), CurrentUser.get().structureId())
                .stream().findFirst().orElse(new Settings(null, false));
    }

    @Transactional
    public Settings updateSignatory(String signatory) {
        var clean = signatory == null || signatory.isBlank() ? null : signatory.strip();
        if (clean != null && clean.length() > 120) {
            throw ApiException.rule("signatory_length", "Le nom du signataire est limité à 120 caractères.");
        }
        upsert("signatory", clean);
        return settings();
    }

    @Transactional
    public Settings updateStamp(byte[] image) {
        var structureId = CurrentUser.get().structureId();
        var previous = jdbc.queryForList("SELECT stamp_file_id FROM document_settings WHERE structure_id = ? AND stamp_file_id IS NOT NULL",
                UUID.class, structureId);
        UUID fileId = null;
        if (image != null) {
            fileId = fileStore.store(structureId, image, Uploads.checkPhoto(image)).getId();
            // Le fichier est enregistré par JPA : il doit exister avant l'écriture SQL qui le référence.
            files.flush();
        }
        upsert("stamp_file_id", fileId);
        previous.forEach(id -> files.findById(id).ifPresent(fileStore::delete));
        audit.record(AuditAction.DOCUMENT_TEMPLATE_CHANGED, "structure", structureId, Map.of("stamp", image != null));
        return settings();
    }

    private void upsert(String column, Object value) {
        var structureId = CurrentUser.get().structureId();
        jdbc.update("INSERT INTO document_settings (structure_id, " + column + ", updated_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (structure_id) DO UPDATE SET " + column + " = excluded." + column + ", updated_at = excluded.updated_at",
                structureId, value, Timestamp.from(clock.instant()));
    }

    private String stampDataUri() {
        var ids = jdbc.queryForList("SELECT stamp_file_id FROM document_settings WHERE structure_id = ? AND stamp_file_id IS NOT NULL",
                UUID.class, CurrentUser.get().structureId());
        if (ids.isEmpty()) {
            return null;
        }
        return files.findById(ids.getFirst()).map(f -> "data:%s;base64,%s".formatted(f.getContentType(),
                Base64.getEncoder().encodeToString(fileStore.read(f)))).orElse(null);
    }

    // --- Outils -------------------------------------------------------------------------------

    private static final String SELECT = "SELECT t.id, t.subject, v.title, v.body, v.version, v.valid_from, v.author, t.retired_at "
            + "FROM document_template t JOIN document_template_version v ON v.template_id = t.id "
            + "AND v.version = (SELECT max(version) FROM document_template_version WHERE template_id = t.id) ";

    private TemplateDto load(UUID id) {
        return jdbc.query(SELECT + "WHERE t.id = ? AND t.structure_id = ?", (rs, i) -> dto(rs), id, CurrentUser.get().structureId())
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Modèle introuvable."));
    }

    private static TemplateDto dto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TemplateDto(rs.getObject("id", UUID.class), Subject.valueOf(rs.getString("subject")), rs.getString("title"),
                rs.getString("body"), rs.getInt("version"), rs.getDate("valid_from").toLocalDate(), rs.getString("author"),
                rs.getTimestamp("retired_at") != null);
    }

    private static String line(String... parts) {
        var kept = java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).toList();
        return kept.isEmpty() ? null : String.join(" · ", kept);
    }

}
