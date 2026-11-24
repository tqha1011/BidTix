package com.bidtix.devseed;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.event.dtos.CreateEventRequest;
import com.bidtix.event.dtos.CreateVenueRequest;
import com.bidtix.event.dtos.CreateZoneRequest;
import com.bidtix.event.dtos.CreateZoneRequest.SeatRowRequest;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.dtos.VenueResponse;
import com.bidtix.event.dtos.ZoneResponse;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.event.service.EventService;
import com.bidtix.event.service.VenueService;
import com.bidtix.event.service.ZoneService;
import com.bidtix.payment.service.PayableOrders;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Fills an empty development database with sample users, venues, events, zones, seats and a few
 * paid tickets, so every endpoint has something to show.
 * <p>
 * Off unless {@code bidtix.seed.enabled=true} (env {@code BIDTIX_SEED_ENABLED=true}, or {@code make run-seed}).
 * It goes through the real services, so the data passes the same validation as API calls, and it is safe to
 * start twice: when the seed admin already exists it does nothing.
 * Never enable it in production: every account uses the same well-known password.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bidtix.seed.enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    public static final String ADMIN_EMAIL = "admin@bidtix.dev";
    public static final String ORGANIZER_EMAIL = "organizer@bidtix.dev";
    public static final String PASSWORD = "Password123!";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final VenueService venueService;
    private final EventService eventService;
    private final ZoneService zoneService;
    private final BookingService bookingService;
    private final PayableOrders payableOrders;
    private final TicketZoneRepository ticketZoneRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail(ADMIN_EMAIL)) {
            log.info("Dev seed skipped: {} already exists", ADMIN_EMAIL);
            return;
        }

        createUser(ADMIN_EMAIL, "Dev Admin", UserRole.ADMIN);
        User organizer = createUser(ORGANIZER_EMAIL, "Dev Organizer", UserRole.ORGANIZER);
        List<User> customers = List.of(
                createUser("customer1@bidtix.dev", "Customer One", UserRole.CUSTOMER),
                createUser("customer2@bidtix.dev", "Customer Two", UserRole.CUSTOMER),
                createUser("customer3@bidtix.dev", "Customer Three", UserRole.CUSTOMER));

        VenueResponse stadium = venueService.create(
                new CreateVenueRequest("Sân vận động Quốc gia Mỹ Đình", "Lê Đức Thọ, Nam Từ Liêm", "Hà Nội"));
        VenueResponse theatre = venueService.create(
                new CreateVenueRequest("Nhà hát Hòa Bình", "240 Ba Tháng Hai, Quận 10", "TP. Hồ Chí Minh"));

        UUID organizerId = organizer.getPublicId();
        EventResponse live = createEvent(organizerId, stadium, "BidTix Live 2027", 60);
        ZoneResponse vip = zoneService.createZone(organizerId, live.publicId(), seated("VIP", 2_000_000,
                new SeatRowRequest("A", 1, 10), new SeatRowRequest("B", 1, 10)));
        ZoneResponse standard = zoneService.createZone(organizerId, live.publicId(), seated("Standard", 800_000,
                new SeatRowRequest("C", 1, 20), new SeatRowRequest("D", 1, 20),
                new SeatRowRequest("E", 1, 20), new SeatRowRequest("F", 1, 20)));
        zoneService.createZone(organizerId, live.publicId(),
                new CreateZoneRequest("Standing", ZoneType.GENERAL_ADMISSION, 400_000, 400, null));

        EventResponse acoustic = createEvent(organizerId, theatre, "Acoustic Night", 30);
        ZoneResponse balcony = zoneService.createZone(organizerId, acoustic.publicId(), seated("Balcony", 500_000,
                new SeatRowRequest("A", 1, 15), new SeatRowRequest("B", 1, 15)));

        // paid tickets, so the admin ticket list is not empty (the mock gateway always accepts the payment)
        buy(customers.get(0), live, vip, 2);
        buy(customers.get(1), live, standard, 3);
        buy(customers.get(2), acoustic, balcony, 1);

        log.info("Dev seed done. Log in with {} / {} / customer1..3@bidtix.dev, password {}",
                ADMIN_EMAIL, ORGANIZER_EMAIL, PASSWORD);
    }

    private User createUser(String email, String fullName, UserRole role) {
        return userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), fullName, role));
    }

    private EventResponse createEvent(UUID organizerId, VenueResponse venue, String title, int startsInDays) {
        Instant now = clock.instant();
        Instant startsAt = now.plus(startsInDays, ChronoUnit.DAYS);
        return eventService.createEvent(organizerId, new CreateEventRequest(venue.publicId(), title,
                "Sample event created by the dev seed", startsAt, startsAt.plus(3, ChronoUnit.HOURS),
                now.minus(1, ChronoUnit.DAYS), startsAt.minus(1, ChronoUnit.DAYS), 6));
    }

    private static CreateZoneRequest seated(String name, long price, SeatRowRequest... rows) {
        return new CreateZoneRequest(name, ZoneType.SEATED, price, null, List.of(rows));
    }

    /** Books the first {@code count} free seats of the zone for the customer and pays for them (booking only holds the seats). */
    private void buy(User customer, EventResponse event, ZoneResponse zone, int count) {
        long zoneId = ticketZoneRepository.findByPublicId(zone.publicId()).orElseThrow().getId();
        List<UUID> seats = seatRepository.findAllAvailableSeatByTicketZoneId(zoneId).stream()
                .limit(count).map(seat -> seat.getPublicId()).toList();
        var booked = bookingService.bookTicket(
                new BookingTicketRequest(event.publicId(), zone.publicId(), seats, null), customer.getPublicId());
        var order = payableOrders.findOwnOrder(booked.orderCode(), customer.getPublicId()).orElseThrow();
        payableOrders.applyPayment(order.id(), clock.instant());
    }
}
