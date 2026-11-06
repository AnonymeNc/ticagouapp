package nc.ticagou.billing;

import java.sql.Array;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import nc.ticagou.billing.BillingDtos.InvoiceStatus;
import nc.ticagou.billing.BillingDtos.RequestStatus;

/**
 * État de paiement des factures et des demandes aux financeurs, déduit des avoirs et du lettrage
 * (jamais stocké : une facture émise ne change plus).
 */
@Component
public class InvoiceBalances {

    /** {@code credited} : avoirs (part famille) ; {@code paid} : lettrage des paiements non annulés. */
    public record Balance(long credited, boolean cancelled, long paid, LocalDate sentOn, int reminderLevel) {

        public long remaining(long familyTotal) {
            return cancelled ? 0 : Math.max(familyTotal - credited - paid, 0);
        }

        public InvoiceStatus status(Invoice invoice, LocalDate today) {
            long remaining = remaining(invoice.getFamilyTotal());
            if (cancelled) {
                return InvoiceStatus.ANNULEE;
            }
            if (remaining == 0) {
                return InvoiceStatus.PAYEE;
            }
            if (invoice.getDueOn().isBefore(today)) {
                return InvoiceStatus.EN_RETARD;
            }
            return paid > 0 || credited > 0 ? InvoiceStatus.PARTIELLE : InvoiceStatus.EMISE;
        }

        public long daysLate(Invoice invoice, LocalDate today) {
            return remaining(invoice.getFamilyTotal()) > 0 && invoice.getDueOn().isBefore(today)
                    ? ChronoUnit.DAYS.between(invoice.getDueOn(), today) : 0;
        }
    }

    private static final Balance NONE = new Balance(0, false, 0, null, 0);

    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    public InvoiceBalances(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.zone = clock.getZone();
    }

    public Balance of(UUID invoiceId) {
        return of(List.of(invoiceId)).getOrDefault(invoiceId, NONE);
    }

    public Map<UUID, Balance> of(Collection<UUID> invoiceIds) {
        var result = new HashMap<UUID, Balance>();
        if (invoiceIds.isEmpty()) {
            return result;
        }
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT i.id,
                           COALESCE((SELECT sum(c.family_total) FROM credit_note c WHERE c.invoice_id = i.id), 0) AS credited,
                           EXISTS (SELECT 1 FROM credit_note c WHERE c.invoice_id = i.id AND c.full_cancel) AS cancelled,
                           COALESCE((SELECT sum(a.amount) FROM payment_allocation a JOIN payment p ON p.id = a.payment_id
                                     WHERE a.invoice_id = i.id AND p.cancelled_at IS NULL), 0) AS paid,
                           (SELECT max(e.created_at) FROM invoice_event e WHERE e.invoice_id = i.id AND e.kind = 'ENVOYEE') AS sent_at,
                           COALESCE((SELECT max(r.level) FROM reminder r WHERE r.invoice_id = i.id), 0) AS reminder_level
                    FROM invoice i WHERE i.id = ANY (?)""");
            Array ids = connection.createArrayOf("uuid", invoiceIds.toArray());
            statement.setArray(1, ids);
            return statement;
        }, rs -> {
            var sentAt = rs.getTimestamp("sent_at");
            result.put(rs.getObject("id", UUID.class), new Balance(rs.getLong("credited"), rs.getBoolean("cancelled"),
                    rs.getLong("paid"), sentAt == null ? null : sentAt.toInstant().atZone(zone).toLocalDate(),
                    rs.getInt("reminder_level")));
        });
        return result;
    }

    /** Montant reçu de chaque demande au financeur (lettrage des paiements non annulés). */
    public Map<UUID, Long> received(Collection<UUID> requestIds) {
        var result = new HashMap<UUID, Long>();
        if (requestIds.isEmpty()) {
            return result;
        }
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT a.funder_request_id, sum(a.amount) AS received
                    FROM payment_allocation a JOIN payment p ON p.id = a.payment_id
                    WHERE a.funder_request_id = ANY (?) AND p.cancelled_at IS NULL
                    GROUP BY a.funder_request_id""");
            statement.setArray(1, connection.createArrayOf("uuid", requestIds.toArray()));
            return statement;
        }, rs -> {
            result.put(rs.getObject("funder_request_id", UUID.class), rs.getLong("received"));
        });
        return result;
    }

    public static RequestStatus requestStatus(FunderRequest request, long received) {
        if (request.getTotal() > 0 && received >= request.getTotal()) {
            return RequestStatus.REGLEE;
        }
        if (received > 0) {
            return RequestStatus.PARTIELLE;
        }
        return request.isSent() ? RequestStatus.ENVOYEE : RequestStatus.A_ENVOYER;
    }
}
