package com.shopstream.inventory.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockReservationRepository extends JpaRepository<StockReservation, String> {
}
