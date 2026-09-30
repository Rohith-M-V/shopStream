package com.shopstream.order.web;

import com.shopstream.common.events.EventEnvelope;
import com.shopstream.common.events.EventTypes;
import com.shopstream.order.dto.CreateOrderRequest;
import com.shopstream.order.dto.OrderResponse;
import com.shopstream.order.exception.OrderNotFoundException;
import com.shopstream.order.order.Order;
import com.shopstream.order.order.OrderCreatedPayload;
import com.shopstream.order.order.OrderRepository;
import com.shopstream.order.order.OrderStatus;
import com.shopstream.order.outbox.OutboxEvent;
import com.shopstream.order.outbox.OutboxEventRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
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

    /**
     * Reacts to an InventoryReserved event by moving the order to
     * PAYMENT_PENDING -- the next stage once payment (Phase 5) exists.
     *
     * <p>The idempotency guard here is the order's own status, not a separate
     * dedup table: this only does anything if the order is still PENDING. A
     * redelivered InventoryReserved event (Kafka's at-least-once delivery
     * means this WILL happen sometimes) finds the order already past PENDING
     * and simply no-ops, rather than trying to apply the same transition
     * twice or overwrite a later state.
     */
    @Transactional
    public void handleInventoryReserved(String orderId) {
        applyTransitionIfPending(orderId, OrderStatus.PAYMENT_PENDING, "InventoryReserved");
    }

    /**
     * Reacts to an InventoryFailed event by cancelling the order. No
     * compensation is needed here -- nothing was ever reserved, so there is
     * nothing to undo. (Compensation -- releasing an ALREADY-reserved
     * quantity -- becomes relevant once payment can fail after a successful
     * reservation, in Phase 5.)
     */
    @Transactional
    public void handleInventoryFailed(String orderId, String reason) {
        log.info("Order {} cancelled: inventory reported {}", orderId, reason);
        applyTransitionIfPending(orderId, OrderStatus.CANCELLED, "InventoryFailed");
    }

    private void applyTransitionIfPending(String orderId, OrderStatus newStatus, String eventName) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            // Defensive only -- should not happen, since the order is always
            // written before its OrderCreated event is even published.
            log.warn("Received {} for unknown order {}", eventName, orderId);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            log.debug("Ignoring {} for order {}: already in status {}", eventName, orderId, order.getStatus());
            return;
        }
        order.setStatus(newStatus);
        orderRepository.save(order);
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
