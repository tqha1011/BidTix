package com.bidtix.auction.controller;

import com.bidtix.auction.dtos.PlaceBidRequest;
import com.bidtix.auction.service.AuctionService;
import com.bidtix.auction.service.BidService;
import com.bidtix.booking.infrastructure.BookingGateway;
import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Calling an auction off: only while nobody has bid, and the ticket goes back on sale exactly once. */
class CancelAuctionApiTest extends ApiTest {

    @Autowired AuctionService auctionService;
    @Autowired BidService bidService;
    @Autowired BookingGateway gateway;

    private record Auctioned(UUID organizer, UUID zone, UUID seat, UUID auction) {
    }

    @Test
    void aScheduledAuctionIsCancelled_andItsSeatIsOnSaleAgain() throws Exception {
        Auctioned a = seated("SCHEDULED");
        gateway.setSeatFlagged(a.zone(), List.of(a.seat()));

        cancel(a.organizer(), a.auction()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(seatStatus(a.seat())).isEqualTo("AVAILABLE");
        assertThat(gateway.flaggedSeats(a.zone(), List.of(a.seat()))).isEmpty();
    }

    @Test
    void aLiveAuctionNobodyBidOn_givesTheStandingTicketBack() throws Exception {
        Auctioned a = standing("LIVE");
        assertThat(available(a.zone())).isEqualTo(4);

        cancel(a.organizer(), a.auction()).andExpect(status().isOk());

        assertThat(available(a.zone())).isEqualTo(5);
    }

    @Test
    void anAuctionWithABid_orOneThatIsOver_cannotBeCancelled() throws Exception {
        Auctioned bidOn = standing("LIVE");
        bidService.placeBid(PlaceBidRequest.create(bidOn.auction(), 100_000L), user("CUSTOMER"));
        List<Auctioned> refused = new ArrayList<>(List.of(bidOn));
        for (String status : new String[]{"AWAITING_PAYMENT", "SETTLED", "FAILED", "CANCELLED"}) {
            refused.add(standing(status));
        }

        for (Auctioned a : refused) {
            cancel(a.organizer(), a.auction()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("AUCTION_NOT_CANCELLABLE"));
            assertThat(available(a.zone())).as("nothing given back").isEqualTo(4);
        }
    }

    @Test
    void onlyTheEventsOrganizerMayCancel() throws Exception {
        Auctioned a = standing("SCHEDULED");

        cancel(user("ORGANIZER"), a.auction()).andExpect(status().isForbidden());
        cancel(a.organizer(), UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/auctions/{id}/cancel", a.auction()).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isForbidden());
        assertThat(available(a.zone())).isEqualTo(4);
    }

    @Test
    void parallelCancels_giveTheTicketBackOnce() throws Exception {
        Auctioned a = standing("SCHEDULED");
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> auctionService.cancelAuction(a.organizer(), a.auction()));
        }

        assertThat(succeeded(calls)).isEqualTo(1);
        assertThat(available(a.zone())).isEqualTo(5);
    }

    @Test
    void aBidAndACancelAtTheSameTime_neverBothWin() throws Exception {
        for (int round = 0; round < 30; round++) {
            Auctioned a = standing("LIVE");
            UUID bidder = user("CUSTOMER");

            int won = succeeded(List.of(
                    () -> bidService.placeBid(PlaceBidRequest.create(a.auction(), 100_000L), bidder),
                    () -> auctionService.cancelAuction(a.organizer(), a.auction())));

            assertThat(won).isEqualTo(1);
            boolean cancelled = jdbc.queryForObject("select status = 'CANCELLED' from auctions where public_id = ?",
                    Boolean.class, a.auction());
            int bids = jdbc.queryForObject("select count(*) from bids b join auctions x on x.id = b.auction_id "
                    + "where x.public_id = ?", Integer.class, a.auction());
            assertThat(bids).isEqualTo(cancelled ? 0 : 1);
            assertThat(available(a.zone())).isEqualTo(cancelled ? 5 : 4);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static int succeeded(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> results = new ArrayList<>();
            for (Callable<Object> call : calls) {
                results.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            int succeeded = 0;
            for (Future<Object> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException refused) {
                    assertThat(refused.getCause()).isInstanceOf(com.bidtix.config.exceptions.BusinessException.class);
                }
            }
            return succeeded;
        } finally {
            pool.shutdownNow();
        }
    }

    private ResultActions cancel(UUID organizer, UUID auction) throws Exception {
        return mvc.perform(post("/api/v1/auctions/{id}/cancel", auction).with(as(organizer, "ORGANIZER")));
    }

    private Auctioned seated(String status) {
        UUID organizer = user("ORGANIZER");
        UUID zone = Catalog.seatedZone(jdbc, Catalog.event(jdbc, organizer, "PUBLISHED"), 1);
        UUID seat = Catalog.seats(jdbc, zone).get(0);
        jdbc.update("update seats set status = 'RESERVED_FOR_AUCTION' where public_id = ?", seat);
        return new Auctioned(organizer, zone, seat, auction(zone, seat, status));
    }

    /** A standing zone of 5 with one ticket taken for the auction. */
    private Auctioned standing(String status) {
        UUID organizer = user("ORGANIZER");
        UUID zone = Catalog.standingZone(jdbc, Catalog.event(jdbc, organizer, "PUBLISHED"), 5, 4);
        return new Auctioned(organizer, zone, null, auction(zone, null, status));
    }

    private UUID auction(UUID zone, UUID seat, String status) {
        UUID publicId = UUID.randomUUID();
        jdbc.update("insert into auctions (public_id, event_id, zone_id, seat_id, start_price, min_increment, starts_at, "
                + "ends_at, status) select ?, z.event_id, z.id, (select id from seats where public_id = ?), 100000, 10000, "
                + "now() - interval '1 hour', now() + interval '1 hour', ? from ticket_zones z where z.public_id = ?",
                publicId, seat, status, zone);
        return publicId;
    }

    private int available(UUID zone) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where public_id = ?", Integer.class, zone);
    }

    private String seatStatus(UUID seat) {
        return jdbc.queryForObject("select status from seats where public_id = ?", String.class, seat);
    }
}
