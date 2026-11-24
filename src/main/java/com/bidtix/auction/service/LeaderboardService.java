package com.bidtix.auction.service;

import com.bidtix.auction.dtos.Entry;
import com.bidtix.auction.dtos.LeaderboardEntryResponse;
import com.bidtix.auction.infrastructure.AuctionLeaderBoard;
import com.bidtix.auction.infrastructure.AuctionRepository;
import com.bidtix.auction.infrastructure.BidRepository;
import com.bidtix.auction.infrastructure.LeaderboardProperties;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class LeaderboardService {
    private final LeaderboardProperties leaderboardProperties;
    private final AuctionLeaderBoard auctionLeaderBoard;
    private final BidRepository bidRepository;
    private final AuctionRepository auctionRepository;
    private final UserRepository userRepository;

    public List<LeaderboardEntryResponse> getAuctionLeaderboard(UUID auctionPublicId) {
        var topN = leaderboardProperties.topN() > 0 ? leaderboardProperties.topN() : 10;

        var auction = auctionRepository.findByPublicId(auctionPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        var leaderboard = buildLeaderboard(auctionPublicId, topN);
        var bidderNameMap = findUsernamesAndConvertToHashMap(leaderboard.stream().map(Entry::bidderId).toList());

        return IntStream.range(0, leaderboard.size())
                .mapToObj(i -> {
                    var entry = leaderboard.get(i);
                    var bidderName = bidderNameMap.getOrDefault(entry.bidderId(), "Unknown");
                    return new LeaderboardEntryResponse(
                            i + 1,
                            bidderName,
                            entry.amount()
                    );
                })
                .toList();
    }

    private Map<Long,String> findUsernamesAndConvertToHashMap(Collection<Long> bidderIds){
        return userRepository.findByIdIn(bidderIds).stream()
                .collect(Collectors.toMap(UserRepository.UserName::id, UserRepository.UserName::fullName));
    }

    private List<Entry> buildLeaderboard(UUID auctionPublicId, int topN){
        var leaderboard = auctionLeaderBoard.getLeaderboard(auctionPublicId);
        List<Entry> rebuildLeaderboard;
        if(leaderboard == null || leaderboard.isEmpty()) {
            rebuildLeaderboard = bidRepository.findTopBids(auctionPublicId, PageRequest.of(0, topN));
            auctionLeaderBoard.setLeaderboard(auctionPublicId, rebuildLeaderboard);
        } else {
            rebuildLeaderboard = leaderboard;
        }
        return rebuildLeaderboard;
    }
}
