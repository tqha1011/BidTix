package com.bidtix.event.domain;

import com.bidtix.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "venues")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Venue extends AuditableEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "address", nullable = false, length = 500)
    private String address;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    public Venue(String name, String address, String city) {
        this.name = name;
        this.address = address;
        this.city = city;
    }
}
