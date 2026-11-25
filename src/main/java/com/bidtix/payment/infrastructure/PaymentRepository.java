package com.bidtix.payment.infrastructure;

import com.bidtix.payment.domain.Payment;
import com.bidtix.payment.domain.PaymentProvider;
import com.bidtix.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** The payment a provider's callback names; (provider, txn_ref) is unique and indexed. */
    Optional<Payment> findByProviderAndTxnRef(PaymentProvider provider, String txnRef);

    /** Those of these orders that have a PENDING payment. */
    @Query("select p.orderId from Payment p where p.orderId in :orderIds "
            + "and p.status = com.bidtix.payment.domain.PaymentStatus.PENDING")
    Set<Long> findOrderIdsWithPendingPayment(@Param("orderIds") Collection<Long> orderIds);

    /** Moves a PENDING payment to its result. Check the row count: 0 means it was not PENDING at this version any more. */
    @Modifying
    @Query("update Payment p " +
            "set p.status = :status, p.providerTxnId = :providerTxnId, p.failureReason = :failureReason, " +
            "p.updatedAt = :now, p.version = p.version + 1 " +
            "where p.txnRef = :txnRef and p.status = com.bidtix.payment.domain.PaymentStatus.PENDING " +
            "and p.version = :version")
    int updatePaymentStatusByTxnRef(@Param("txnRef") String txnRef,
                                    @Param("providerTxnId") String providerTxnId,
                                    @Param("status") PaymentStatus status,
                                    @Param("failureReason") String failureReason,
                                    @Param("version") long version,
                                    @Param("now") Instant now);



    /**
     * Takes a REFUND_PENDING payment to be refunded, with one conditional UPDATE. 1 means this caller took it and may
     * ask the provider, after the transaction has committed; 0 means somebody else already did, or it does not wait for
     * a refund. Nothing clears the mark.
     */
    @Modifying
    @Query("update Payment p set p.refundRequestedAt = :now, p.updatedAt = :now, p.version = p.version + 1 "
            + "where p.id = :id and p.status = com.bidtix.payment.domain.PaymentStatus.REFUND_PENDING " +
            "and p.refundRequestedAt is null ")
    int markRefundRequested(@Param("id") long id, @Param("now") Instant now);

    /**
     * Ids of up to {@code limit} payments in this status whose refund has not been requested yet, oldest first. Meant for
     * {@code REFUND_PENDING}. Payments without {@code requested_at} (made before it was stored) are left out: VNPAY
     * cannot refund them without it, and they would fill every batch. {@code FOR UPDATE SKIP LOCKED}: call it inside the
     * transaction that marks them, so another instance skips these rows instead of waiting for them.
     */
    @Query(value = "select * from payments "
            + "where status = :#{#status.name()} and refund_requested_at is null and requested_at is not null "
            + "order by created_at, id limit :limit "
            + "for update skip locked", nativeQuery = true)
    List<Payment> findPaymentWithStatus(@Param("status") PaymentStatus status, @Param("limit") int limit);
}
