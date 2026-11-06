package nc.ticagou.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Page de résultats sérialisée de façon stable (le format JSON de {@link Page} n'est pas garanti). */
public record PageDto<T>(List<T> items, long total, int page, int size) {

    public static <E, T> PageDto<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageDto<>(page.map(mapper).getContent(), page.getTotalElements(), page.getNumber(), page.getSize());
    }
}
