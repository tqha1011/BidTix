package com.bidtix.booking.service;

import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.payment.service.PayableOrders;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The booking module's side of {@link PayableOrders}: what the payment module may ask of an order. */
@Service
@RequiredArgsConstructor
public class OrderPayment implements PayableOrders {

    private final OrderRepository orderRepository;
    private final TicketIssuer ticketIssuer;

    @Override
    @Transactional(readOnly = true)
    public Optional<PayableOrder> findOwnOrder(String orderCode, UUID userPublicId) {
        return orderRepository.findByOrderCodeAndUserId(orderCode, userPublicId).map(OrderPayment::toPayable);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PayableOrder> findOrder(Long orderId) {
        return orderRepository.findById(orderId).map(OrderPayment::toPayable);
    }

    /**
     * Marks the order PAID with one conditional UPDATE (only a PENDING_PAYMENT order matches), then issues the tickets, all in
     * the caller's transaction. The row count decides: 0 means it was not waiting for payment any more, and a re-read of its
     * status says whether another payment paid it ({@code ALREADY_PAID}) or it expired or was cancelled ({@code NOT_PAYABLE}).
     * Two confirmations at once, or a confirmation against the job that cancels expired orders, cannot both win.
     * <p>
     * One case is an exception, not a result: if a seat's hold was lost meanwhile, {@link TicketIssuer} throws
     * {@code ORDER_NOT_PAYABLE}. The transaction must roll back (the order goes back to PENDING_PAYMENT, no seat is sold),
     * and the caller treats it as {@link Applied#NOT_PAYABLE} in a new transaction.
     * <p>
     * Auction orders are not handled yet: they need the offer accepted and the auction settled too.
     */
    @Override
    @Transactional
    public Applied applyPayment(Long orderId, Instant paidAt) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOTFOUND));
        if (order.getSource() == OrderSource.AUCTION) {
            // TODO(auction-payment): accept the offer, settle the auction and move the reserved seat to SOLD
            throw new UnsupportedOperationException("Paying an auction order is not implemented yet");
        }

        if (orderRepository.markOrderPaid(orderId, paidAt) != 1) {
            OrderStatus status = orderRepository.findStatusById(orderId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOTFOUND));
            return status == OrderStatus.PAID ? Applied.ALREADY_PAID : Applied.NOT_PAYABLE;
        }
        ticketIssuer.issue(order);
        return Applied.APPLIED;
    }

    private static PayableOrder toPayable(Order order) {
        return new PayableOrder(order.getId(), order.getOrderCode(), order.getTotalAmount(), order.getExpiresAt(),
                order.getStatus() == OrderStatus.PENDING_PAYMENT);
    }
}
