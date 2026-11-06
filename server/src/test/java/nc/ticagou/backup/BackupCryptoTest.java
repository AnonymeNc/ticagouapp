package nc.ticagou.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import nc.ticagou.backup.BackupRetention.Archive;

class BackupCryptoTest {

    static final String KEY = "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ";

    static byte[] content(int size) {
        var bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    @Test
    void allerRetourSurPlusieursBlocs() throws IOException {
        for (int size : new int[] {0, 10, BackupCrypto.CHUNK, BackupCrypto.CHUNK + 1, 3 * BackupCrypto.CHUNK + 777}) {
            var plain = content(size);
            var sealed = BackupCrypto.encrypt(plain, KEY);
            assertThat(BackupCrypto.decrypt(sealed, KEY)).as("taille %d", size).isEqualTo(plain);
        }
    }

    @Test
    void laCleSeSaisitSansTiretsNiMajuscules() throws IOException {
        var sealed = BackupCrypto.encrypt(content(100), KEY);
        assertThat(BackupCrypto.decrypt(sealed, "abcd efgh jklm npqr stuv wxyz")).isEqualTo(content(100));
    }

    @Test
    void mauvaiseCleArchiveTronqueeOuModifiee() throws IOException {
        var sealed = BackupCrypto.encrypt(content(2 * BackupCrypto.CHUNK + 5), KEY);
        assertThatThrownBy(() -> BackupCrypto.decrypt(sealed, "ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ"))
                .hasMessageContaining("Clé de récupération incorrecte");
        var truncated = Arrays.copyOf(sealed, sealed.length - (BackupCrypto.CHUNK / 2));
        assertThatThrownBy(() -> BackupCrypto.decrypt(truncated, KEY)).isInstanceOf(IOException.class);
        // Le dernier bloc retiré : l'archive paraît finie après le deuxième bloc, ce qui doit être détecté.
        int header = BackupCrypto.MAGIC.length + 16 + 8;
        int block = 4 + BackupCrypto.CHUNK + 16;
        var withoutLast = Arrays.copyOf(sealed, header + 2 * block);
        assertThatThrownBy(() -> BackupCrypto.decrypt(withoutLast, KEY)).hasMessageContaining("dernier bloc");
        var tampered = sealed.clone();
        tampered[header + 100] ^= 1;
        assertThatThrownBy(() -> BackupCrypto.decrypt(tampered, KEY)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> BackupCrypto.decrypt("pas une sauvegarde".getBytes(), KEY))
                .hasMessageContaining("n’est pas une sauvegarde");
    }

    @Test
    void cleDeRecuperation() {
        var key = BackupCrypto.newRecoveryKey();
        assertThat(key).matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){5}");
        assertThat(key).doesNotContain("0", "1", "O", "I");
        assertThat(BackupCrypto.newRecoveryKey()).isNotEqualTo(key);
    }

    @Test
    void rotationSeptJoursQuatreSemainesDouzeMois() {
        // Dimanche 01/11/2026 : une sauvegarde par nuit depuis septembre 2025.
        var today = LocalDate.of(2026, 11, 1);
        var archives = new java.util.ArrayList<Archive>();
        for (var day = LocalDate.of(2025, 9, 1); !day.isAfter(today); day = day.plusDays(1)) {
            archives.add(new Archive("ticagou-%s-0200.tcb".formatted(day), day));
        }
        var deleted = BackupRetention.toDelete(archives, today, 7, 4, 12);
        var kept = archives.stream().filter(a -> !deleted.contains(a)).map(Archive::day).toList();

        // 7 derniers jours : du 26/10 au 01/11.
        assertThat(kept).containsAll(today.minusDays(6).datesUntil(today.plusDays(1)).toList());
        // 4 semaines, celle en cours comprise : le lundi des semaines du 05/10, 12/10 et 19/10.
        assertThat(kept).contains(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19))
                .doesNotContain(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 13));
        // 12 mois, celui en cours compris : le 1er de chaque mois de décembre 2025 à octobre 2026.
        assertThat(kept).contains(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))
                .doesNotContain(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 9, 2));
        assertThat(kept).hasSize(7 + 3 + 11);
        assertThat(BackupRetention.toDelete(List.of(new Archive("a", today.minusDays(40))), today, 7, 0, 0)).hasSize(1);
    }

    @Test
    void alerteSansSauvegardeDepuisQuaranteHuitHeures() {
        var now = Instant.parse("2026-11-03T08:00:00Z");
        assertThat(BackupService.isStale(now.minus(Duration.ofHours(47)), null, now)).isFalse();
        assertThat(BackupService.isStale(now.minus(Duration.ofHours(49)), null, now)).isTrue();
        assertThat(BackupService.isStale(null, now.minus(Duration.ofDays(1)), now)).as("installée hier").isFalse();
        assertThat(BackupService.isStale(null, now.minus(Duration.ofDays(3)), now)).isTrue();
        assertThat(BackupService.isStale(null, null, now)).as("pas encore installée").isFalse();
    }
}
