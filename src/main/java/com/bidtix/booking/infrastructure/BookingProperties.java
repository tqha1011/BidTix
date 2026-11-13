package com.bidtix.booking.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bidtix.booking")
public record BookingProperties(
        @DefaultValue("15") int orderExpirationMinutes,
        /* seats or standing tickets in one order */
        @DefaultValue("5") int maxTicketsPerOrder
) {

}
