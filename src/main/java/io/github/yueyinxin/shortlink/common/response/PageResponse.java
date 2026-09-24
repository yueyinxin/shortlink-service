package io.github.yueyinxin.shortlink.common.response;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应。
 *
 * <p>不直接把 Spring Data 的 {@code Page} 序列化返回，原因有二：
 * <ol>
 *   <li>{@code Page} 的 JSON 结构不稳定 —— 它带有 {@code pageable}、{@code sort}、
 *       {@code first}/{@code last} 等大量字段，且不同 Spring Data 版本间会变化，
 *       把框架的内部结构暴露成对外契约是危险的。</li>
 *   <li>显式声明字段可以只暴露客户端真正需要的信息。</li>
 * </ol>
 *
 * @param content       当前页数据
 * @param page          当前页码，从 0 开始
 * @param size          每页大小
 * @param totalElements 总记录数
 * @param totalPages    总页数
 * @param hasNext       是否还有下一页
 * @param <T>           元素类型
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    /** 由 Spring Data 的 {@code Page} 转换，并对元素做映射。 */
    public static <E, T> PageResponse<T> from(Page<E> source, Function<E, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.hasNext()
        );
    }

    /** 空分页结果。用于无法直接构造 {@code Page} 的场景。 */
    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0L, 0, false);
    }
}
