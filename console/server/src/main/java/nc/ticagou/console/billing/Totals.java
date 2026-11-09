package nc.ticagou.console.billing;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Totaux d'une pièce : total HT, TGC par taux arrondie au franc sur le total hors taxe de ce taux (pas ligne par ligne),
 * total TTC. Les lignes portent leur montant HT et leur taux en dixièmes de pour cent.
 */
public final class Totals {

    private Totals() {
    }

    public record Line(long amount, int tgcRate) {
    }

    public record RateTotal(int rate, long base, long tgc) {
    }

    public record Result(long ht, long tgc, long ttc, List<RateTotal> rates) {
    }

    public static Result of(List<Line> lines) {
        var bases = new TreeMap<Integer, Long>();
        long ht = 0;
        for (var line : lines) {
            ht += line.amount();
            bases.merge(line.tgcRate(), line.amount(), Long::sum);
        }
        var rates = new ArrayList<RateTotal>();
        long tgc = 0;
        for (var entry : bases.entrySet()) {
            long amount = Xpf.percent(entry.getValue(), entry.getKey());
            rates.add(new RateTotal(entry.getKey(), entry.getValue(), amount));
            tgc += amount;
        }
        return new Result(ht, tgc, ht + tgc, rates);
    }
}
