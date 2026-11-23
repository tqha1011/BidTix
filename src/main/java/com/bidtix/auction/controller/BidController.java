package com.bidtix.auction.controller;

import com.bidtix.auction.dtos.BidAmountRequest;
import com.bidtix.auction.dtos.PlaceBidRequest;
import com.bidtix.auction.dtos.PlaceBidResponse;
import com.bidtix.auction.service.BidService;
import com.bidtix.config.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/** Bids on an auction. Only customers bid; the default per-user rate limit applies. */
@RestController
@RequestMapping("/api/v1/auctions/{auctionPublicId}/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;

    /**
     * Places a bid for the logged-in customer. 201 when accepted; 400 when the auction is over or not open
     * ({@code BID_NO_LONGER_AVAILABLE}) or the amount is too low ({@code INVALID_MONEY_BID}); 404 for an unknown auction.
     * Needs an {@code Idempotency-Key} header (checked by {@code IdempotencyFilter}): a retry with the same key and
     * body replays this response instead of bidding twice.
     */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<PlaceBidResponse> placeBid(@PathVariable UUID auctionPublicId,
                                                     @Valid @RequestBody BidAmountRequest request,
                                                     Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bidService.placeBid(PlaceBidRequest.create(auctionPublicId, request.amount()),
                        CurrentUser.publicId(principal)));
    }
}
