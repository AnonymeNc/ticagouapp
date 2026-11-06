package nc.ticagou.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nc.ticagou.config.TiCagouProperties;

class EncryptedFileStoreTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final byte[] PAI = "PAI de Noa Wahéo : arachide, adrénaline".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    EncryptedFileStore store;

    @BeforeEach
    void setUp() throws Exception {
        var repository = mock(StoredFileRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var properties = new TiCagouProperties(ZoneId.of("Pacific/Noumea"), List.of(), null,
                new TiCagouProperties.Storage(dir, KEY), null, null, null, null, null, null);
        var clock = Clock.fixed(Instant.parse("2026-10-30T22:00:00Z"), ZoneId.of("Pacific/Noumea"));
        store = new EncryptedFileStore(repository, properties, null, clock);
    }

    @Test
    void chiffreSurDisqueEtRelitLeContenu() throws Exception {
        var file = store.store(UUID.randomUUID(), PAI, "application/pdf");

        var onDisk = Files.readAllBytes(dir.resolve(file.getStoragePath()));
        assertThat(new String(onDisk, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("TCG1");
        assertThat(new String(onDisk, StandardCharsets.ISO_8859_1)).doesNotContain("arachide");
        assertThat(file.getStoragePath()).endsWith(file.getId() + ".bin").doesNotContain("Noa");
        assertThat(file.getSizeBytes()).isEqualTo(PAI.length);

        assertThat(store.read(file)).isEqualTo(PAI);
    }

    @Test
    void detecteUnFichierAltere() throws Exception {
        var file = store.store(UUID.randomUUID(), PAI, "application/pdf");
        var path = dir.resolve(file.getStoragePath());
        var bytes = Files.readAllBytes(path);
        bytes[bytes.length - 1] ^= 1;
        Files.write(path, bytes);

        assertThatThrownBy(() -> store.read(file)).hasMessageContaining("altéré");
    }

    @Test
    void unContenuNePeutPasRemplacerCeluiDUnAutreDocument() throws Exception {
        var first = store.store(UUID.randomUUID(), PAI, "application/pdf");
        var second = store.store(UUID.randomUUID(), "autre".getBytes(), "application/pdf");
        Files.copy(dir.resolve(first.getStoragePath()), dir.resolve(second.getStoragePath()),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThatThrownBy(() -> store.read(second)).hasMessageContaining("altéré");
    }

    @Test
    void supprimeLeFichierHorsTransaction() {
        var file = store.store(UUID.randomUUID(), PAI, "application/pdf");

        store.delete(file);

        assertThat(dir.resolve(file.getStoragePath())).doesNotExist();
    }
}
