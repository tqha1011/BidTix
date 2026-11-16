package com.bidtix.booking.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The gate on its own, with Redis mocked: what it answers for each flag value, and that it never fails the request. */
@ExtendWith(MockitoExtension.class)
class BookingGatewayTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;

    private final UUID zone = UUID.randomUUID();
    private BookingGateway gateway;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
        gateway = new BookingGateway(properties(true), redis);
    }

    private static BookingProperties properties(boolean gateEnabled) {
        return new BookingProperties(15, 5, gateEnabled, 3);
    }

    // ---- quantity (standing zones) ---------------------------------------------------------

    @Test
    void noFlag_letsEveryQuantityThrough() {
        when(ops.get(anyString())).thenReturn(null);

        assertThat(gateway.mayTakeQuantity(zone, 1)).isTrue();
        assertThat(gateway.mayTakeQuantity(zone, 5)).isTrue();
    }

    @Test
    void aFlag_rejectsThatQuantityAndEveryLargerOne_butNotSmallerOnes() {
        when(ops.get(anyString())).thenReturn("4");

        assertThat(gateway.mayTakeQuantity(zone, 3)).as("smaller: may still fit").isTrue();
        assertThat(gateway.mayTakeQuantity(zone, 4)).as("the very quantity the database refused").isFalse();
        assertThat(gateway.mayTakeQuantity(zone, 5)).as("larger").isFalse();
    }

    @Test
    void aFlagOfOne_meansSoldOut() {
        when(ops.get(anyString())).thenReturn("1");

        assertThat(gateway.mayTakeQuantity(zone, 1)).isFalse();
    }

    @Test
    void redisDown_letsTheRequestThrough() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(gateway.mayTakeQuantity(zone, 2)).isTrue();
    }

    @Test
    void aValueThatIsNotANumber_letsTheRequestThrough() {
        when(ops.get(anyString())).thenReturn("sold-out");

        assertThat(gateway.mayTakeQuantity(zone, 2)).isTrue();
    }

    @Test
    void settingTheFlag_usesTheConfiguredTtl_andTheZoneInTheKey() {
        gateway.setQuantityRejected(zone, 3);

        verify(ops).set(contains(zone.toString()), eq("3"), eq(Duration.ofSeconds(3)));
    }

    @Test
    void settingTheFlag_swallowsRedisErrors() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> gateway.setQuantityRejected(zone, 3)).doesNotThrowAnyException();
    }

    // ---- seats -----------------------------------------------------------------------------

    @Test
    void flaggedSeats_returnsOnlyTheSeatsThatHaveAFlag_inRequestOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList(null, "1", "1"));

        assertThat(gateway.flaggedSeats(zone, List.of(a, b, c))).containsExactly(b, c);
    }

    @Test
    void flaggedSeats_isEmpty_whenNothingIsFlagged_orRedisAnswersNull() {
        UUID a = UUID.randomUUID();
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList((String) null));
        assertThat(gateway.flaggedSeats(zone, List.of(a))).isEmpty();

        when(ops.multiGet(anyList())).thenReturn(null);
        assertThat(gateway.flaggedSeats(zone, List.of(a))).isEmpty();
    }

    @Test
    void flaggedSeats_redisDown_letsTheRequestThrough() {
        when(ops.multiGet(anyList())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(gateway.flaggedSeats(zone, List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void setSeatFlagged_swallowsRedisErrors() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> gateway.setSeatFlagged(zone, List.of(UUID.randomUUID()))).doesNotThrowAnyException();
    }

    // ---- switched off ----------------------------------------------------------------------

    @Test
    void whenDisabled_theGateNeverTouchesRedis_andNeverRejects() {
        BookingGateway off = new BookingGateway(properties(false), redis);

        assertThat(off.mayTakeQuantity(zone, 5)).isTrue();
        assertThat(off.flaggedSeats(zone, List.of(UUID.randomUUID()))).isEmpty();
        off.setQuantityRejected(zone, 5);
        off.setSeatFlagged(zone, List.of(UUID.randomUUID()));

        verifyNoInteractions(redis);
    }
}
