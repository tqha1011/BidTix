package com.bidtix.user.infrastructure;

import com.bidtix.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, Long> {

    /** {@code email} must already be lowercase (emails are stored normalized). */
    Optional<User> findByEmail(String email);

    Optional<User> findByPublicId(UUID publicId);

    boolean existsByEmail(String email);

    /** A user's id and display name: all that a leaderboard line needs, without loading the entity. */
    record UserName(Long id, String fullName) {
    }

    /** The names of these users. An id that does not exist is simply missing from the result. */
    List<UserName> findByIdIn(Collection<Long> ids);
}
