package com.bidtix.booking.dtos;

import java.util.List;

/** One order with its lines. */
public record OrderDetailResponse(OrderSummaryResponse order, List<OrderItemResponse> items) {
}
