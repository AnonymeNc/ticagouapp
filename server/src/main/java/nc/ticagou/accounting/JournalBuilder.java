package nc.ticagou.accounting;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import nc.ticagou.common.Formats;

/**
 * Écritures comptables d'une période, sans accès à la base : chaque pièce (facture, avoir, encaissement, annulation
 * d'encaissement) devient des lignes équilibrées. Les montants restent des francs XPF entiers.
 * <ul>
 *   <li>facture : débit du client famille (reste à charge) et de chaque financeur (aides) ; crédit des produits par
 *       nature de ligne (les déductions d'absence viennent en moins) et de la TGC ; l'écart d'arrondi du total est
 *       porté au compte d'arrondis ;</li>
 *   <li>avoir : débit des avoirs accordés, crédit du client famille et des financeurs ;</li>
 *   <li>encaissement : débit de la banque, de la caisse ou du compte d'attente selon le moyen, crédit du client ;
 *       une annulation contrepasse l'encaissement à sa date.</li>
 * </ul>
 */
public final class JournalBuilder {

    public enum Journal { VENTES, BANQUE, CAISSE }

    public enum Kind { FORFAIT, ADAPTATION, FRAIS_INSCRIPTION, HEURES_COMPLEMENTAIRES, DEPASSEMENT, ABSENCE, AIDE }

    public enum Method { VIREMENT, CHEQUE, ESPECES, CARTE }

    public record Party(boolean funder, UUID id, String name) {
    }

    public record InvoiceLine(Kind kind, long amount, long tgcAmount, UUID funderId, String funderName) {
    }

    public record Invoice(String number, LocalDate issuedOn, Party family, long familyTotal, List<InvoiceLine> lines) {
    }

    /** Avoir : part de la famille et part des aides annulées, par financeur. */
    public record CreditNote(String number, String invoiceNumber, LocalDate issuedOn, Party family, long familyTotal,
                             Map<Party, Long> aids) {
    }

    /** Encaissement (ou son annulation, {@code cancelled} vrai, datée du jour de l'annulation). */
    public record Payment(String number, LocalDate day, Party payer, Method method, long amount, String invoices,
                          boolean cancelled) {
    }

    /** Ligne d'écriture : {@code debit} et {@code credit} positifs, l'un des deux nul. */
    public record Line(Journal journal, String piece, LocalDate day, AccountRole role, String account,
                       String accountLabel, Party party, String label, long debit, long credit) {
    }

    /** Compte d'un rôle à une date, ou vide s'il n'est pas paramétré. */
    public interface Accounts {
        Optional<ResolvedAccount> at(AccountRole role, LocalDate day);
    }

    public record ResolvedAccount(String account, String label) {
    }

    public record Result(List<Line> lines, List<String> anomalies) {

        public long debits() {
            return lines.stream().mapToLong(Line::debit).sum();
        }

        public long credits() {
            return lines.stream().mapToLong(Line::credit).sum();
        }
    }

    private final Accounts accounts;
    private final List<Line> lines = new ArrayList<>();
    private final List<String> anomalies = new ArrayList<>();

    private JournalBuilder(Accounts accounts) {
        this.accounts = accounts;
    }

    public static Result build(Accounts accounts, List<Invoice> invoices, List<CreditNote> creditNotes,
                               List<Payment> payments) {
        var builder = new JournalBuilder(accounts);
        invoices.forEach(builder::invoice);
        creditNotes.forEach(builder::creditNote);
        payments.forEach(builder::payment);
        return new Result(List.copyOf(builder.lines), List.copyOf(builder.anomalies));
    }

    private void invoice(Invoice invoice) {
        var piece = new ArrayList<Line>();
        var label = "Facture " + invoice.number() + " — " + invoice.family().name();
        add(piece, Journal.VENTES, invoice.number(), invoice.issuedOn(), AccountRole.CLIENTS_FAMILLES, invoice.family(),
                label, invoice.familyTotal());
        var aids = new LinkedHashMap<UUID, InvoiceLine>();
        var products = new LinkedHashMap<AccountRole, Long>();
        long tgc = 0;
        for (var line : invoice.lines()) {
            if (line.kind() == Kind.AIDE) {
                aids.merge(line.funderId(), line, (a, b) -> new InvoiceLine(Kind.AIDE, a.amount() + b.amount(), 0,
                        a.funderId(), a.funderName()));
            } else {
                products.merge(product(line.kind()), line.amount(), Long::sum);
                tgc += line.tgcAmount();
            }
        }
        for (var aid : aids.values()) {
            // Les lignes d'aide sont négatives sur la facture : l'aide est due par le financeur.
            add(piece, Journal.VENTES, invoice.number(), invoice.issuedOn(), AccountRole.CLIENTS_FINANCEURS,
                    new Party(true, aid.funderId(), aid.funderName()), label, -aid.amount());
        }
        products.forEach((role, amount) -> add(piece, Journal.VENTES, invoice.number(), invoice.issuedOn(), role, null,
                label, -amount));
        add(piece, Journal.VENTES, invoice.number(), invoice.issuedOn(), AccountRole.TGC_COLLECTEE, null, label, -tgc);
        balance(piece, Journal.VENTES, invoice.number(), invoice.issuedOn(), label);
        lines.addAll(piece);
    }

    private void creditNote(CreditNote note) {
        var piece = new ArrayList<Line>();
        var label = "Avoir " + note.number() + " sur " + note.invoiceNumber() + " — " + note.family().name();
        long total = note.familyTotal() + note.aids().values().stream().mapToLong(Long::longValue).sum();
        add(piece, Journal.VENTES, note.number(), note.issuedOn(), AccountRole.AVOIRS, null, label, total);
        add(piece, Journal.VENTES, note.number(), note.issuedOn(), AccountRole.CLIENTS_FAMILLES, note.family(), label,
                -note.familyTotal());
        note.aids().forEach((funder, amount) -> add(piece, Journal.VENTES, note.number(), note.issuedOn(),
                AccountRole.CLIENTS_FINANCEURS, funder, label, -amount));
        lines.addAll(piece);
    }

    private void payment(Payment payment) {
        var piece = new ArrayList<Line>();
        var journal = payment.method() == Method.ESPECES ? Journal.CAISSE : Journal.BANQUE;
        var label = (payment.cancelled() ? "Annulation règlement " : "Règlement ") + payment.payer().name()
                + (payment.invoices() == null || payment.invoices().isBlank() ? "" : " — " + payment.invoices());
        long sign = payment.cancelled() ? -1 : 1;
        var treasury = switch (payment.method()) {
            case VIREMENT -> AccountRole.BANQUE;
            case CHEQUE -> AccountRole.CHEQUES;
            case ESPECES -> AccountRole.CAISSE;
            case CARTE -> AccountRole.CARTE;
        };
        var client = payment.payer().funder() ? AccountRole.CLIENTS_FINANCEURS : AccountRole.CLIENTS_FAMILLES;
        add(piece, journal, payment.number(), payment.day(), treasury, null, label, sign * payment.amount());
        add(piece, journal, payment.number(), payment.day(), client, payment.payer(), label, -sign * payment.amount());
        lines.addAll(piece);
    }

    static AccountRole product(Kind kind) {
        return switch (kind) {
            case FRAIS_INSCRIPTION -> AccountRole.PRODUITS_INSCRIPTION;
            case HEURES_COMPLEMENTAIRES, DEPASSEMENT -> AccountRole.PRODUITS_SUPPLEMENTS;
            default -> AccountRole.PRODUITS_GARDE;
        };
    }

    /** Montant signé : positif au débit, négatif au crédit ; un montant nul ne donne pas de ligne. */
    private void add(List<Line> piece, Journal journal, String number, LocalDate day, AccountRole role, Party party,
                     String label, long signed) {
        if (signed == 0) {
            return;
        }
        var account = accounts.at(role, day);
        if (account.isEmpty()) {
            var message = "Aucun compte « %s » en vigueur le %s (Paramètres › Comptabilité)."
                    .formatted(role.label(), Formats.date(day));
            if (!anomalies.contains(message)) {
                anomalies.add(message);
            }
        }
        var resolved = account.orElse(new ResolvedAccount("", role.label()));
        piece.add(new Line(journal, number, day, role, resolved.account(), resolved.label(), party, label,
                Math.max(signed, 0), Math.max(-signed, 0)));
    }

    /** Écart entre débit et crédit d'une facture (arrondi du total) : porté au compte d'arrondis. */
    private void balance(List<Line> piece, Journal journal, String number, LocalDate day, String label) {
        long gap = piece.stream().mapToLong(l -> l.debit() - l.credit()).sum();
        add(piece, journal, number, day, AccountRole.ARRONDIS, null, label, -gap);
    }
}
