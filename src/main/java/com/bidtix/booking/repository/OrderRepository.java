package com.bidtix.booking.repository;

import com.bidtix.booking.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
