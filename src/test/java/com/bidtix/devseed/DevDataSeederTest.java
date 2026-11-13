package com.bidtix.devseed;

import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "bidtix.seed.enabled=true")
class DevDataSeederTest extends PostgresIntegrationTest {

    @Autowired DevDataSeeder seeder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void seedsUsableData_andIsSafeToRunAgain() throws Exception {
        // the context start already ran the seeder once; running it again must change nothing
        int usersBefore = count("select count(*) from users where email like '%@bidtix.dev'");
        int ticketsBefore = count("select count(*) from tickets");
        seeder.run(new DefaultApplicationArguments());

        assertThat(count("select count(*) from users where email like '%@bidtix.dev'")).isEqualTo(usersBefore).isEqualTo(5);
        assertThat(count("select count(*) from tickets")).isEqualTo(ticketsBefore);

        assertThat(count("select count(*) from users where email = 'admin@bidtix.dev' and role = 'ADMIN'")).isOne();
        assertThat(count("select count(*) from users where email = 'organizer@bidtix.dev' and role = 'ORGANIZER'")).isOne();
        String hash = jdbc.queryForObject("select password_hash from users where email = 'admin@bidtix.dev'", String.class);
        assertThat(passwordEncoder.matches(DevDataSeeder.PASSWORD, hash)).isTrue();

        // BidTix Live: 20 + 80 seats and a standing zone; Acoustic Night: 30 seats
        assertThat(count("select count(*) from seats s join ticket_zones z on z.id = s.zone_id "
                + "join events e on e.id = z.event_id where e.title = 'BidTix Live 2027'")).isEqualTo(100);
        assertThat(count("select available_quantity from ticket_zones where name = 'Standing' and capacity = 400"))
                .isEqualTo(400);
        // 2 + 3 + 1 paid tickets, and their seats are SOLD
        assertThat(count("select count(*) from tickets t join users u on u.id = t.owner_id "
                + "where u.email like 'customer_@bidtix.dev'")).isEqualTo(6);
        assertThat(count("select count(*) from seats s join tickets t on t.seat_id = s.id "
                + "where s.status = 'SOLD'")).isGreaterThanOrEqualTo(6);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
