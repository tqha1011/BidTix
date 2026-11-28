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
     * The order's paid payment now waits for a refund: SUCCEEDED -&gt; REFUND_PENDING. At most one payment of an order is
     * SUCCEEDED ({@code ux_payments_one_succeeded_per_order}), so the row count is 1, or 0 when there is none.
     */
    @Modifying
    @Query("update Payment p set p.status = com.bidtix.payment.domain.PaymentStatus.REFUND_PENDING, "
            + "p.failureReason = :reason, p.updatedAt = :now, p.version = p.version + 1 "
            + "where p.orderId = :orderId and p.status = com.bidtix.payment.domain.PaymentStatus.SUCCEEDED")
    int markRefundPendingByOrderId(@Param("orderId") long orderId, @Param("reason") String reason,
                                   @Param("now") Instant now);

    /**
     * Takes a REFUND_PENDING payment to be refunded, with one conditional UPDATE. 1 means this caller took it and may
     * ask the provider, after the transaction has committed; 0 means somebody else already did, or it does not wait for
     * a refund. It counts the attempt. Only {@link #releaseRefundClaim} takes the mark away again.
     */
    @Modifying
    @Query("update Payment p set p.refundRequestedAt = :now, p.refundAttempts = p.refundAttempts + 1, "
            + "p.updatedAt = :now, p.version = p.version + 1 "
            + "where p.id = :id and p.status = com.bidtix.payment.domain.PaymentStatus.REFUND_PENDING " +
            "and p.refundRequestedAt is null ")
    int markRefundRequested(@Param("id") long id, @Param("now") Instant now);

    /**
     * Gives a claim back, for a request that certainly never reached the provider: the payment can be claimed again from
     * {@code nextAttemptAt}. Never for one whose outcome is unknown, or the money could be returned twice. Check the row
     * count.
     */
    @Modifying
    @Query("update Payment p set p.refundRequestedAt = null, p.refundNextAttemptAt = :nextAttemptAt, "
            + "p.failureReason = :reason, p.updatedAt = :now, p.version = p.version + 1 "
            + "where p.id = :id and p.status = com.bidtix.payment.domain.PaymentStatus.REFUND_PENDING "
            + "and p.refundRequestedAt is not null")
    int releaseRefundClaim(@Param("id") long id, @Param("reason") String reason,
                           @Param("nextAttemptAt") Instant nextAttemptAt, @Param("now") Instant now);

    /** Writes why a requested refund did not happen. Check the row count: 0 means it does not wait for one any more. */
    @Modifying
    @Query("update Payment p set p.failureReason = :reason, p.updatedAt = :now, p.version = p.version + 1 "
            + "where p.id = :id and p.status = com.bidtix.payment.domain.PaymentStatus.REFUND_PENDING "
            + "and p.refundRequestedAt is not null")
    int recordRefundFailure(@Param("id") long id, @Param("reason") String reason, @Param("now") Instant now);

    /**
     * Up to {@code limit} payments in this status that can be claimed for a refund at {@code now}, oldest first: not
     * claimed yet, and past the waiting time of an earlier attempt. Meant for {@code REFUND_PENDING}. Payments without
     * {@code requested_at} (made before it was stored) are left out: VNPAY cannot refund them without it, and they would
     * fill every batch. {@code FOR UPDATE SKIP LOCKED}: call it inside the transaction that marks them, so another
     * instance skips these rows instead of waiting for them.
     */
    @Query(value = "select * from payments "
            + "where status = :#{#status.name()} and refund_requested_at is null and requested_at is not null "
            + "and (refund_next_attempt_at is null or refund_next_attempt_at <= :now) "
            + "order by created_at, id limit :limit "
            + "for update skip locked", nativeQuery = true)
    List<Payment> findPaymentWithStatus(@Param("status") PaymentStatus status, @Param("now") Instant now,
                                        @Param("limit") int limit);


    @Modifying
    @Query("update Payment p " +
            "set p.status = PaymentStatus.REFUNDED, p.updatedAt = :now, p.version = p.version + 1 " +
            "where p.id = :id and p.status = PaymentStatus.REFUND_PENDING and p.refundRequestedAt is not null")
    int markRefundSucceeded(@Param("id") long id, @Param("now") Instant now);

    /**
     * Up to {@code limit} requested refunds whose outcome should be asked about at {@code now}: still REFUND_PENDING,
     * requested at or before {@code requestedBefore} (not while the request may still be on its way), fewer than
     * {@code maxQueries} queries made, and past the waiting time of the last one. Those never queried come first.
     * {@code FOR UPDATE SKIP LOCKED}: call it inside the transaction that claims them.
     */
    @Query(value = "select * from payments "
            + "where status = 'REFUND_PENDING' and refund_requested_at <= :requestedBefore and requested_at is not null "
            + "and refund_queries < :maxQueries "
            + "and (refund_next_query_at is null or refund_next_query_at <= :now) "
            + "order by refund_next_query_at nulls first, id limit :limit "
            + "for update skip locked", nativeQuery = true)
    List<Payment> findRefundsToConfirm(@Param("now") Instant now, @Param("requestedBefore") Instant requestedBefore,
                                       @Param("maxQueries") int maxQueries, @Param("limit") int limit);

    /**
     * Takes one due query: counts it and sets when the next one may be made. 0 means somebody else took it, or the
     * payment does not wait for a refund any more. Check the row count.
     */
    @Modifying
    @Query("update Payment p set p.refundQueries = p.refundQueries + 1, p.refundNextQueryAt = :nextQueryAt, "
            + "p.updatedAt = :now, p.version = p.version + 1 "
            + "where p.id = :id and p.status = com.bidtix.payment.domain.PaymentStatus.REFUND_PENDING "
            + "and p.refundRequestedAt is not null "
            + "and (p.refundNextQueryAt is null or p.refundNextQueryAt <= :now)")
    int claimRefundQuery(@Param("id") long id, @Param("nextQueryAt") Instant nextQueryAt, @Param("now") Instant now);

    /**
     * No more scheduled queries about this refund: its answer is known (rejected).
     */
    @Modifying
    @Query("update Payment p set p.refundQueries = :maxQueries, p.updatedAt = :now, p.version = p.version + 1 "
            + "where p.id = :id and p.status = com.bidtix.payment.domain.PaymentStatus.REFUND_PENDING")
    void stopRefundQueries(@Param("id") long id, @Param("maxQueries") int maxQueries, @Param("now") Instant now);

    long countByStatus(PaymentStatus status);

    long countByStatusAndCreatedAtBefore(PaymentStatus status, Instant createdBefore);

    /** When the oldest payment still waiting for a refund was created. */
    @Query("select min(p.createdAt) from Payment p "
            + "where p.status = com.bidtix.payment.domain.PaymentStatus.REFUND_PENDING")
    Optional<Instant> findOldestRefundPendingCreation();

    /**
     * Refunds the job will not finish by itself: no more requests or no more queries are made, or the payment is too old
     * to be refunded through the API (no {@code requested_at}). A fifth request that is still on its way is counted too.
     */
    @Query("select count(p) from Payment p where p.status = com.bidtix.payment.domain.PaymentStatus.REFUND_PENDING "
            + "and (p.refundAttempts >= :maxAttempts or p.refundQueries >= :maxQueries or p.requestedAt is null)")
    long countRefundsNeedingAPerson(@Param("maxAttempts") int maxAttempts, @Param("maxQueries") int maxQueries);


    /**
     * Up to {@code limit} PENDING payments due to be asked about at {@code now}: made at or before
     * {@code requestedBefore} (younger ones are left to the callback), asked about fewer than {@code maxQueries} times,
     * and past the waiting time of the last query. Newest first, so that a customer who is waiting does not queue behind
     * old payments. Payments without {@code requested_at} never match: VNPAY cannot be asked about them.
     * {@code FOR UPDATE SKIP LOCKED}: call it inside the transaction that takes them with {@link #markStatusQueried}.
     */
    @Query(value = "select * from payments "
            + "where status = 'PENDING' and requested_at <= :requestedBefore "
            + "and status_queries < :maxQueries "
            + "and (next_status_query_at is null or next_status_query_at <= :now) "
            + "order by requested_at desc, id desc limit :limit "
            + "for update skip locked", nativeQuery = true)
    List<Payment> findPaymentsToQuery(@Param("now") Instant now, @Param("requestedBefore") Instant requestedBefore,
                                      @Param("maxQueries") int maxQueries, @Param("limit") int limit);

    /**
     * Takes one due query: counts it and sets when the next one may be made. 0 means somebody else took it, or the
     * payment is not PENDING any more. Check the row count. It leaves {@code version} alone on purpose: a callback that
     * read the payment just before must still be able to record its result.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Payment p " +
            "set p.statusQueries = p.statusQueries + 1, p.nextStatusQueryAt = :nextQueryAt, p.updatedAt = :now "
            + "where p.id = :id and p.status = com.bidtix.payment.domain.PaymentStatus.PENDING "
            + "and (p.nextStatusQueryAt is null or p.nextStatusQueryAt <= :now)")
    int markStatusQueried(@Param("id") long id, @Param("nextQueryAt") Instant nextQueryAt, @Param("now") Instant now);
}
