package com.bidtix.user.infrastructure;

import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository.UserName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code findByIdIn}: the display names of a handful of users, in one query and without loading the entities. */
class UserNamesTest extends PostgresIntegrationTest {

    @Autowired UserRepository users;

    @Test
    void returnsTheIdAndNameOfEachAskedUser() {
        User ann = users.save(new User("names-" + UUID.randomUUID() + "@test.dev", "hash", "Ann", UserRole.CUSTOMER));
        User bob = users.save(new User("names-" + UUID.randomUUID() + "@test.dev", "hash", "Bob", UserRole.CUSTOMER));
        users.save(new User("names-" + UUID.randomUUID() + "@test.dev", "hash", "Not asked", UserRole.CUSTOMER));

        List<UserName> names = users.findByIdIn(Set.of(ann.getId(), bob.getId()));

        assertThat(names).containsExactlyInAnyOrder(new UserName(ann.getId(), "Ann"), new UserName(bob.getId(), "Bob"));
    }

    @Test
    void anUnknownId_isMissingFromTheResult() {
        User ann = users.save(new User("names-" + UUID.randomUUID() + "@test.dev", "hash", "Ann", UserRole.CUSTOMER));

        assertThat(users.findByIdIn(Set.of(ann.getId(), Long.MAX_VALUE))).containsExactly(new UserName(ann.getId(), "Ann"));
    }

    @Test
    void anEmptyListOfIds_givesAnEmptyResult() {
        assertThat(users.findByIdIn(Set.of())).isEmpty();
    }
}
