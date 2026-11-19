package com.bidtix.auction.controller;

import com.bidtix.auction.dtos.AuctionResponse;
import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.service.AuctionService;
import com.bidtix.config.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/** Auctions. Creating one is an organizer endpoint; who owns the event is checked in the service. */
@RestController
@RequestMapping("/api/v1/auctions")
@RequiredArgsConstructor
public class AuctionController {

    private final AuctionService auctionService;

    /** Creates a SCHEDULED auction for one seat or one standing ticket and takes that ticket out of normal sale. */
    @PostMapping
    @PreAuthorize("hasRole('ORGANIZER')")
    public ResponseEntity<AuctionResponse> createAuction(@Valid @RequestBody CreateAuctionRequest request,
                                                         Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(auctionService.createAuction(CurrentUser.publicId(principal), request));
    }
}
