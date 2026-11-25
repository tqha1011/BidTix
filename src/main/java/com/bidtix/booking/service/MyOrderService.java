package com.bidtix.booking.service;

import com.bidtix.booking.dtos.OrderDetailResponse;
import com.bidtix.booking.dtos.OrderSummaryResponse;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.common.dtos.PageResponse;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** What a customer reads about their own orders. Everything is looked up by the caller, so nothing else can be seen. */
@Service
@RequiredArgsConstructor
public class MyOrderService {

    static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;

    /** The caller's orders, newest first. Bad paging values are corrected (negative page -> 0, size clamped to 1..100). */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> list(UUID userPublicId, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(orderRepository.findSummariesOfUser(userPublicId, pageable));
    }

    /** One of the caller's orders with its lines. Somebody else's order is as unknown as no order at all. */
    @Transactional(readOnly = true)
    public OrderDetailResponse get(UUID userPublicId, String orderCode) {
        var order = orderRepository.findSummaryOfUser(userPublicId, orderCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOTFOUND));
        return new OrderDetailResponse(order, orderRepository.findItemViews(orderCode));
    }
}
