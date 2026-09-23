package com.shopstream.inventory.product;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsBySku(String sku);
}
