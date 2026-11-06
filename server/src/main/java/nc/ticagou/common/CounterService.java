package nc.ticagou.common;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Numérotation continue par structure, par série et par année (dossiers enfants, puis factures).
 * L'incrément est atomique en base : deux créations simultanées n'obtiennent jamais le même numéro.
 */
@Service
public class CounterService {

    private final JdbcTemplate jdbc;

    public CounterService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int next(UUID structureId, String series, int year) {
        return jdbc.queryForObject("""
                INSERT INTO structure_counter (structure_id, name, year, value) VALUES (?, ?, ?, 1)
                ON CONFLICT (structure_id, name, year) DO UPDATE SET value = structure_counter.value + 1
                RETURNING value""", Integer.class, structureId, series, year);
    }
}
