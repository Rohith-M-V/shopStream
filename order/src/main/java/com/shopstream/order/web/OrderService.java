package com.shopstream.order.web;

import com.shopstream.common.events.EventEnvelope;
import com.shopstream.common.events.EventTypes;
import com.shopstream.order.dto.CreateOrderRequest;
import com.shopstream.order.dto.OrderResponse;
import com.shopstream.order.exception.OrderNotFoundException;
import com.shopstream.order.order.Order;
import com.shopstream.order.order.OrderCreatedPayload;
import com.shopstream.order.order.OrderRepository;
import com.shopstream.order.outbox.OutboxEvent;
import com.shopstream.order.outbox.OutboxEventRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final String AGGREGATE_TYPE = "Order";

    // Spring Boot 4 moved to Jackson 3 (tools.jackson.*). Building the mapper
    // directly, rather than injecting a Spring-managed bean, keeps this class
    // independent of which ObjectMapper flavour Boot happens to expose. Jackson 3
    // has java.time (Instant) support built in, so no extra module is needed.
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;

    public OrderService(OrderRepository orderRepository, OutboxEventRepository outboxEventRepository) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Creates an order and its OrderCreated outbox row in one transaction --
     * the actual point of this whole phase. {@code orderId} is the caller's
     * Idempotency-Key, already validated as present by the controller.
     */
    @Transactional
    public OrderResponse createOrder(String orderId, UUID customerId, CreateOrderRequest request) {
        var existing = orderRepository.findById(orderId);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        Order order = Order.create(orderId, customerId, request.productId(), request.quantity());
        orderRepository.save(order);

        OutboxEvent outboxEvent = OutboxEvent.create(
                AGGREGATE_TYPE,
                order.getId(),
                EventTypes.ORDER_CREATED,
                serializeEnvelope(order));
        outboxEventRepository.save(outboxEvent);

        // Both saves above are in this same @Transactional method, against the
        // same database -- they commit together or not at all. That's the
        // entire trick: no separate call to Kafka happens here at all.
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderId, UUID requestingCustomerId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // Same "don't reveal what you can't prove you're allowed to see"
        // principle as auth's login response: a customer asking for someone
        // else's order gets the same 404 as an order that doesn't exist at all,
        // not a 403 that would confirm the order exists.
        if (!order.getCustomerId().equals(requestingCustomerId)) {
            throw new OrderNotFoundException(orderId);
        }
        return toResponse(order);
    }

    private String serializeEnvelope(Order order) {
        var payload = new OrderCreatedPayload(
                order.getId(), order.getCustomerId(), order.getProductId(), order.getQuantity(),
                order.getCreatedAt());
        var envelope = EventEnvelope.of(EventTypes.ORDER_CREATED, order.getId(), payload);
        try {
            return OBJECT_MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            // A serialization failure here means the whole order-creation
            // transaction rolls back -- correct behavior: we'd rather fail the
            // request than silently create an order with no matching event.
            throw new IllegalStateException("Failed to serialize OrderCreated event", e);
        }
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(), order.getCustomerId(), order.getProductId(), order.getQuantity(),
                order.getStatus().name(), order.getCreatedAt());
    }
}
