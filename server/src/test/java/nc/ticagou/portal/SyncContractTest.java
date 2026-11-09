package nc.ticagou.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import nc.ticagou.portal.SyncDtos.AbsenceIn;
import nc.ticagou.portal.SyncDtos.MessageIn;
import nc.ticagou.portal.SyncDtos.SignatureIn;

/**
 * Le format de synchronisation du serveur local correspond aux exemples de {@code parents-portal/contract/}, lus aussi
 * par les tests du portail : chaque champ des exemples est connu, et rien d'autre n'est envoyé.
 */
class SyncContractTest {

    private static final Path CONTRACT = Path.of("..", "parents-portal", "contract");
    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private static final Map<PortalItemType, Class<?>> PAYLOADS = Map.of(
            PortalItemType.STRUCTURE, SyncDtos.StructurePayload.class,
            PortalItemType.GUARDIAN, SyncDtos.GuardianPayload.class,
            PortalItemType.CHILD, SyncDtos.ChildPayload.class,
            PortalItemType.ATTENDANCE, SyncDtos.AttendancePayload.class,
            PortalItemType.ENTRY, SyncDtos.EntryPayload.class,
            PortalItemType.INVOICE, SyncDtos.InvoicePayload.class,
            PortalItemType.DOCUMENT, SyncDtos.DocumentPayload.class,
            PortalItemType.MESSAGE, SyncDtos.MessagePayload.class,
            PortalItemType.NEWS, SyncDtos.NewsPayload.class);

    private static JsonNode read(String name) throws IOException {
        return JSON.readTree(Files.readAllBytes(CONTRACT.resolve(name)));
    }

    @Test
    void envoiConformeAuContrat() throws IOException {
        var batch = read("batch.json");
        var types = new HashSet<PortalItemType>();
        for (var node : batch.get("items")) {
            var item = JSON.treeToValue(node, SyncDtos.BatchItem.class);
            types.add(item.type());
            if (item.deleted()) {
                assertThat(node.get("payload").isNull()).isTrue();
                continue;
            }
            var payload = JSON.treeToValue(node.get("payload"), PAYLOADS.get(item.type()));
            // Aller-retour : mêmes champs, mêmes valeurs.
            assertThat(JSON.valueToTree(payload).toString()).as(item.type().name()).isEqualTo(node.get("payload").toString());
        }
        assertThat(types).containsAll(PAYLOADS.keySet()).contains(PortalItemType.PURGE_FAMILY);
        assertThat(read("batch-response.json").get("acceptedUpTo").asLong()).isEqualTo(11);
    }

    @Test
    void remonteeConformeAuContrat() throws IOException {
        var inbox = JSON.treeToValue(read("inbox.json"), SyncDtos.Inbox.class);
        assertThat(inbox.items()).extracting(SyncDtos.InboxItem::type)
                .containsExactly(SyncDtos.InboxType.ABSENCE, SyncDtos.InboxType.MESSAGE, SyncDtos.InboxType.SIGNATURE);
        var absence = JSON.treeToValue(inbox.items().get(0).payload(), AbsenceIn.class);
        assertThat(absence.toDay()).isAfter(absence.fromDay());
        assertThat(JSON.treeToValue(inbox.items().get(1).payload(), MessageIn.class).body()).isNotBlank();
        assertThat(JSON.treeToValue(inbox.items().get(2).payload(), SignatureIn.class).sha256()).hasSize(64);
        JSON.treeToValue(read("activate.json"), SyncDtos.ActivateRequest.class);
    }

    @Test
    void aucunChampDeSanteDansLesContenus() {
        // Liste blanche : les contenus envoyés ne portent aucun champ de santé (seules les informations partagées
        // explicitement par la direction passent, dans sharedHealth).
        var forbidden = Set.of("allergen", "allergies", "diet", "pai", "vaccin", "doctor", "medic", "instruction",
                "reaction", "severity");
        for (var type : PAYLOADS.values()) {
            for (var component : type.getRecordComponents()) {
                var name = component.getName().toLowerCase();
                assertThat(forbidden).as(type.getSimpleName() + "." + component.getName())
                        .noneMatch(name::contains);
            }
        }
    }
}
