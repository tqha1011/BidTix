package com.bidtix.booking.service;

import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderPaidEvent;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.payment.service.PayableOrders;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

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
     * An auction order also has an offer and an auction to settle. That is done by whoever listens to {@link OrderPaidEvent}
     * (the auction module), and the event is published BEFORE the order row is updated for such an order: the offer expiry job
     * locks the offer first and the order second, so taking the locks in the same order here (offer, then order) means a payment
     * and that job can never wait for each other. If the offer is not open any more, the listener throws
     * {@code ORDER_NOT_PAYABLE}; the same exception comes from {@link TicketIssuer} when a reserved seat is gone. Either way
     * the transaction must roll back (nothing is left half done) and the caller treats the payment as
     * {@link Applied#NOT_PAYABLE} in a new transaction.
     */
    @Override
    @Transactional
    public Applied applyPayment(Long orderId, Instant paidAt) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOTFOUND));
        boolean auction = order.getSource() == OrderSource.AUCTION;

        if (auction) {
            // an unlocked read, only to give the precise answer early; the conditional UPDATEs below decide
            OrderStatus status = statusOf(orderId);
            if (status != OrderStatus.PENDING_PAYMENT) {
                return status == OrderStatus.PAID ? Applied.ALREADY_PAID : Applied.NOT_PAYABLE;
            }
            publishPaid(order, paidAt);
        }

        if (orderRepository.markOrderPaid(orderId, paidAt) != 1) {
            if (auction) {
                // the offer was just accepted, so the order cannot have been anything but pending: undo it all
                throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE, "The order is no longer waiting for payment");
            }
            OrderStatus status = statusOf(orderId);
            return status == OrderStatus.PAID ? Applied.ALREADY_PAID : Applied.NOT_PAYABLE;
        }
        ticketIssuer.issue(order, paidAt);
        if (!auction) {
            publishPaid(order, paidAt);
        }
        return Applied.APPLIED;
    }

    private OrderStatus statusOf(Long orderId) {
        return orderRepository.findStatusById(orderId).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOTFOUND));
    }

    private void publishPaid(Order order, Instant paidAt) {
        eventPublisher.publishEvent(new OrderPaidEvent(order.getId(), order.getOrderCode(), order.getSource(),
                order.getTotalAmount(), paidAt));
    }

    private static PayableOrder toPayable(Order order) {
        return new PayableOrder(order.getId(), order.getOrderCode(), order.getTotalAmount(), order.getExpiresAt(),
                order.getStatus() == OrderStatus.PENDING_PAYMENT);
    }
}
