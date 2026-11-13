package com.bidtix.common.dtos;

import org.springframework.data.domain.Page;

import java.util.List;

/** Our own page shape, so the JSON does not change when Spring Data changes how it serializes {@code Page}. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
