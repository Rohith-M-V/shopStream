package com.shopstream.inventory.dto;

import jakarta.validation.constraints.Min;

public record ReserveStockRequest(@Min(1) int quantity) {
}
