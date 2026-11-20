package com.bidtix.auction.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bidtix.auction")
public record AuctionProperties(
        /* a bid in the last this-many seconds extends the auction, unless the organizer chose another value */
        @DefaultValue("60") int antiSnipeWindowSec,
        /* seconds added by such a bid, unless the organizer chose another value */
        @DefaultValue("60") int extensionSec) {
}
