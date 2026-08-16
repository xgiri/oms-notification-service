package com.giri.oms.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Same uniform paging envelope as every other service in this system —
 * consumers of this API get the same shape regardless of which service
 * they're calling.
 */
public record PagedResponse<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }
}
