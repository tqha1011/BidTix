package com.bidtix.auction.controller;

import com.bidtix.auction.dtos.LeaderboardEntryResponse;
import com.bidtix.auction.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The top bidders of an auction. Any logged-in user may read it; the default per-user rate limit applies. */
@RestController
@RequestMapping("/api/v1/auctions/{auctionPublicId}/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    /**
     * The highest bidders of one auction, highest bid first, each once with their highest bid (at most
     * {@code bidtix.leaderboard.top-n}). Empty list when nobody has bid; 404 for an unknown auction.
     */
    @GetMapping
    public List<LeaderboardEntryResponse> leaderboard(@PathVariable UUID auctionPublicId) {
        return leaderboardService.getAuctionLeaderboard(auctionPublicId);
    }
}
