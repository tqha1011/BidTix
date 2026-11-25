package com.bidtix.auction.controller;

import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One auction and its bid history. */
class AuctionReadApiTest extends ApiTest {

    @Test
    void oneAuctionShowsItsPublicIds_butNeverTheReserve() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.seatedZone(jdbc, event, 1);
        UUID seat = Catalog.seats(jdbc, zone).get(0);
        UUID auction = auction(zone, seat);

        mvc.perform(get("/api/v1/auctions/{id}", auction).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(auction.toString()))
                .andExpect(jsonPath("$.eventPublicId").value(event.toString()))
                .andExpect(jsonPath("$.zonePublicId").value(zone.toString()))
                .andExpect(jsonPath("$.seatPublicId").value(seat.toString()))
                .andExpect(jsonPath("$.startPrice").value(100000))
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.reservePrice").doesNotExist());
    }

    @Test
    void theBidHistoryIsListedHighestFirst_withNamesOnly() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID auction = auction(Catalog.standingZone(jdbc, event, 5, 4), null);
        bid(auction, named("An"), 100_000);
        bid(auction, named("Binh"), 120_000);
        bid(auction, named("Chi"), 150_000);

        mvc.perform(get("/api/v1/auctions/{id}/bids?size=2", auction).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].bidderName").value("Chi"))
                .andExpect(jsonPath("$.items[0].amount").value(150000))
                .andExpect(jsonPath("$.items[0].placedAt").exists())
                .andExpect(jsonPath("$.items[1].bidderName").value("Binh"))
                .andExpect(jsonPath("$.items[0].email").doesNotExist())
                .andExpect(jsonPath("$.items[0].bidderPublicId").doesNotExist());
    }

    @Test
    void anUnknownAuction_isNotFound_andALoginIsNeeded() throws Exception {
        for (String path : new String[]{"/api/v1/auctions/{id}", "/api/v1/auctions/{id}/bids"}) {
            mvc.perform(get(path, UUID.randomUUID()).with(as(user("CUSTOMER"), "CUSTOMER")))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
            mvc.perform(get(path, UUID.randomUUID())).andExpect(status().isUnauthorized());
        }
    }

    private UUID auction(UUID zone, UUID seat) {
        UUID publicId = UUID.randomUUID();
        jdbc.update("insert into auctions (public_id, event_id, zone_id, seat_id, start_price, min_increment, reserve_price, "
                + "starts_at, ends_at, status) select ?, z.event_id, z.id, (select id from seats where public_id = ?), "
                + "100000, 10000, 200000, now() - interval '1 hour', now() + interval '1 hour', 'LIVE' "
                + "from ticket_zones z where z.public_id = ?", publicId, seat, zone);
        return publicId;
    }

    private UUID named(String name) {
        UUID bidder = user("CUSTOMER");
        jdbc.update("update users set full_name = ? where public_id = ?", name, bidder);
        return bidder;
    }

    private void bid(UUID auction, UUID bidder, long amount) {
        jdbc.update("insert into bids (auction_id, bidder_id, amount) select a.id, u.id, ? from auctions a, users u "
                + "where a.public_id = ? and u.public_id = ?", amount, auction, bidder);
    }
}
