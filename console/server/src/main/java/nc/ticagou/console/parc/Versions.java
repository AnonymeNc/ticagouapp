package nc.ticagou.console.parc;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/** Versions de Ti'Cagou (« 1.5.0 », « 1.4.2-rc1 ») : comparées nombre par nombre, le suffixe ignoré. */
final class Versions {

    static final Comparator<String> ORDER = Versions::compare;

    private Versions() {
    }

    static int compare(String a, String b) {
        var x = numbers(a);
        var y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? x[i] : 0;
            long q = i < y.length ? y[i] : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return 0;
    }

    /** Version la plus récente du parc, ou nul si aucune n'est lisible. */
    static String latest(Collection<String> versions) {
        return versions.stream().filter(Objects::nonNull).filter(Versions::readable).max(ORDER).orElse(null);
    }

    static boolean readable(String version) {
        return version != null && version.matches("\\d{1,9}(\\.\\d{1,9})*([-+].*)?");
    }

    private static long[] numbers(String version) {
        if (!readable(version)) {
            return new long[0];
        }
        var core = version.split("[-+]", 2)[0].split("\\.");
        var result = new long[core.length];
        for (int i = 0; i < core.length; i++) {
            result[i] = Long.parseLong(core[i]);
        }
        return result;
    }
}
