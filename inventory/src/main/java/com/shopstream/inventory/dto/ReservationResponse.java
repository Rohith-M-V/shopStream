package com.shopstream.inventory.dto;

import java.util.UUID;

public record ReservationResponse(
        String reservationId,
        UUID productId,
        int quantity,
        String status) {
}
