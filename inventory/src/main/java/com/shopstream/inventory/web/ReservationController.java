package com.shopstream.inventory.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ProductService productService;

    public ReservationController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/{reservationId}/release")
    public ResponseEntity<Void> release(@PathVariable String reservationId) {
        productService.releaseStock(reservationId);
        return ResponseEntity.noContent().build();
    }
}
