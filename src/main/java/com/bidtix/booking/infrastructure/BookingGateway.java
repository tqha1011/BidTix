package com.bidtix.booking.infrastructure;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingGateway {

    private static final String PREFIX = "bidtix-backend:gate:";
    private final BookingProperties properties;
    private final StringRedisTemplate redisTemplate;


    public boolean mayTakeQuantity(UUID zonePublicId, int quantity){
        if(!properties.gateEnabled()){
            return true;
        }

        String key = buildZoneKey(zonePublicId);

        try{
            String rejectedQuantity = redisTemplate.opsForValue().get(key);
            if(rejectedQuantity == null) return true;
            // The flag holds a quantity the database refused: that one and any larger one will be refused too.
            return quantity < Integer.parseInt(rejectedQuantity);
        }
        catch (DataAccessException e){
            log.warn("Redis error while checking gate for zone {}: {}", zonePublicId, e.getMessage());
            return true;
        }
        catch (NumberFormatException e){
            log.warn("Unexpected gate value for zone {}: {}", zonePublicId, e.getMessage());
            return true;
        }
    }

    /**
     * Sets the quantity of tickets that are rejected for a zone. If the gate is disabled, does nothing.
     * Example: if quantity = 3 was rejected, then all the request with quantity >= 3 will be rejected
     * immediately (without checking the database) for a few seconds. This is to avoid a thundering herd of
     * requests that would all fail anyway.
     */
    public void setQuantityRejected(UUID zonePublicId, int quantity){
        if(!properties.gateEnabled()){
            return;
        }

        String key = buildZoneKey(zonePublicId);
        trySet(key, String.valueOf(quantity));
    }


    /**
     * Returns the list of seatPublicIds that are flagged (i.e. have a value in Redis).
     * If the gate is disabled, returns an empty list.
     */
    public List<UUID> flaggedSeats(UUID zonePublicId, List<UUID> seatPublicIds){
        if(!properties.gateEnabled()){
            return List.of();
        }

        var keys = seatPublicIds.stream().map(id -> buildSeatKey(zonePublicId, id)).toList();
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
        }
        catch (DataAccessException e){
            log.warn("Redis error while checking gate for seats: {}", e.getMessage());
            return List.of();
        }
        if(values == null) {
            return List.of();
        }
        return IntStream.range(0, seatPublicIds.size())
                .filter(i -> values.get(i) != null)       // có cờ ⇔ giá trị khác null
                .mapToObj(seatPublicIds::get)
                .toList();
    }

    public void setSeatFlagged(UUID zonePublicId, List<UUID> seatPublicIds){
        if(!properties.gateEnabled()){
            return;
        }

        for(UUID seatPublicId : seatPublicIds){
            String key = buildSeatKey(zonePublicId, seatPublicId);
            trySet(key, "1");
        }
    }

    /**
     * Removes the sold-out flag of a zone. Call it after the database commit that gave tickets back, or
     * customers are refused until the flag expires. A Redis failure is logged and ignored (the flag still expires).
     */
    public void clearQuantityRejected(UUID zonePublicId){
        if(!properties.gateEnabled()){
            return;
        }
        tryDelete(List.of(buildZoneKey(zonePublicId)));
    }

    /** Removes the flags of seats that were given back. Same rules as {@link #clearQuantityRejected}. */
    public void clearSeatFlags(UUID zonePublicId, List<UUID> seatPublicIds){
        if(!properties.gateEnabled() || seatPublicIds.isEmpty()){
            return;
        }
        tryDelete(seatPublicIds.stream().map(id -> buildSeatKey(zonePublicId, id)).toList());
    }

    private String buildZoneKey(UUID zonePublicId){
        return PREFIX + "zone:" + zonePublicId;
    }

    /** The zone is part of the key: a seat named with the wrong zone must not flag the same seat in its own zone. */
    private String buildSeatKey(UUID zonePublicId, UUID seatPublicId){
        return PREFIX + "seat:" + zonePublicId + ":" + seatPublicId;
    }

    private void tryDelete(List<String> keys){
        try {
            redisTemplate.delete(keys);
        }
        catch(DataAccessException e){
            log.warn("Redis error while trying to clear {} gate key(s): {}", keys.size(), e.getMessage());
        }
    }

    private void trySet(String key, String value){
        try {
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(properties.gateTtlSeconds()));
        }
        catch(DataAccessException e){
            log.warn("Redis error while trying to set gate for key {}: {}", key, e.getMessage());
        }
    }
}
