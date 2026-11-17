package com.bidtix.booking.infrastructure;

import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;


@Component
@RequiredArgsConstructor
public class OrderCancellationJob {
    private final OrderRepository orderRepository;
    private final Clock clock;
    private static final int PAGE_SIZE = 100;


    @Scheduled(fixedRate = 60000)
    void cancelExpiredOrders() {
        int page = 0;
        Slice<Order> slice;

        do{
            Pageable pageable = PageRequest.of(page++,PAGE_SIZE, Sort.by("id").ascending());
            slice = orderRepository.findOrdersExpiredToCancel(OrderStatus.PENDING_PAYMENT, clock.instant(),pageable);

            if(!slice.isEmpty()){
                var ids = slice.getContent().stream().map(Order::getId).toList();
                orderRepository.markOrderCancelled(ids, clock.instant());
            }
        }while(slice.hasNext());
    }
}
