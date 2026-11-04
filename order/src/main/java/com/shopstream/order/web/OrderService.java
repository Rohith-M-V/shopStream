package com.shopstream.order.web;

import com.shopstream.common.events.EventEnvelope;
import com.shopstream.common.events.EventTypes;
import com.shopstream.order.dto.CreateOrderRequest;
import com.shopstream.order.dto.OrderResponse;
import com.shopstream.order.exception.OrderNotFoundException;
import com.shopstream.order.order.Order;
import com.shopstream.order.order.OrderEventPayload;
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
        publishOrderEvent(order, EventTypes.ORDER_CREATED);

        // Both writes above are in this same @Transactional method, against the
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
     * PAYMENT_PENDING and -- in the SAME transaction -- publishing
     * PaymentRequested, which is what actually triggers the payment service.
     * Same transactional-outbox guarantee as order creation: the status
     * change and the event announcing it either both commit or neither does.
     */
    @Transactional
    public void handleInventoryReserved(String orderId) {
        Order order = applyTransition(orderId, OrderStatus.PENDING, OrderStatus.PAYMENT_PENDING, "InventoryReserved");
        if (order != null) {
            publishOrderEvent(order, EventTypes.PAYMENT_REQUESTED);
        }
    }

    /**
     * Reacts to an InventoryFailed event by cancelling the order. No
     * compensation needed -- nothing was ever reserved, so there is nothing
     * to undo.
     */
    @Transactional
    public void handleInventoryFailed(String orderId, String reason) {
        log.info("Order {} cancelled: inventory reported {}", orderId, reason);
        Order order = applyTransition(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED, "InventoryFailed");
        if (order != null) {
            // Terminal-outcome event for downstream consumers (e.g. notification).
            publishOrderEvent(order, EventTypes.ORDER_CANCELLED);
        }
    }

    /**
     * Reacts to a PaymentSucceeded event by confirming the order -- the
     * saga's successful end state.
     */
    @Transactional
    public void handlePaymentSucceeded(String orderId) {
        Order order = applyTransition(orderId, OrderStatus.PAYMENT_PENDING, OrderStatus.CONFIRMED,
                "PaymentSucceeded");
        if (order != null) {
            publishOrderEvent(order, EventTypes.ORDER_CONFIRMED);
        }
    }

    /**
     * Reacts to a PaymentFailed event by cancelling the order AND publishing
     * InventoryReleaseRequested -- the saga's one genuine compensating
     * transaction. Unlike handleInventoryFailed, there IS something to undo
     * here: inventory already committed a real reservation to get this order
     * to PAYMENT_PENDING in the first place, so that reservation must now be
     * explicitly released, not just abandoned.
     */
    @Transactional
    public void handlePaymentFailed(String orderId, String reason) {
        log.info("Order {} cancelled: payment failed: {}", orderId, reason);
        Order order = applyTransition(orderId, OrderStatus.PAYMENT_PENDING, OrderStatus.CANCELLED, "PaymentFailed");
        if (order != null) {
            publishOrderEvent(order, EventTypes.ORDER_CANCELLED);
            publishOrderEvent(order, EventTypes.INVENTORY_RELEASE_REQUESTED);
        }
    }

    /**
     * Applies a status transition only if the order is currently in EXACTLY
     * the state the caller expects it to be in ({@code requiredStatus}) --
     * not "any non-terminal state", which would be looser than it sounds.
     * Being precise here matters: a redelivered InventoryReserved event
     * arriving after the order has already moved past PAYMENT_PENDING must
     * be a true no-op, including NOT re-publishing PaymentRequested a second
     * time -- a vaguer guard (e.g. "not already CONFIRMED or CANCELLED")
     * would let that duplicate slip through.
     *
     * <p>Returns the updated order if the transition was applied, or null if
     * it was skipped (unknown order, or not in the expected prior state) --
     * callers use that null to decide whether to publish a follow-on event,
     * since a skipped transition must not trigger the next step of the saga.
     */
    private Order applyTransition(String orderId, OrderStatus requiredStatus, OrderStatus newStatus,
            String eventName) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Received {} for unknown order {}", eventName, orderId);
            return null;
        }
        if (order.getStatus() != requiredStatus) {
            log.debug("Ignoring {} for order {}: expected status {} but was {}",
                    eventName, orderId, requiredStatus, order.getStatus());
            return null;
        }
        order.setStatus(newStatus);
        orderRepository.save(order);
        return order;
    }

    private void publishOrderEvent(Order order, String eventType) {
        var payload = OrderEventPayload.of(order);
        var envelope = EventEnvelope.of(eventType, order.getId(), payload);
        outboxEventRepository.save(OutboxEvent.create(AGGREGATE_TYPE, order.getId(), eventType, serialize(envelope)));
    }

    private String serialize(EventEnvelope<?> envelope) {
        try {
            return OBJECT_MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            // A serialization failure here rolls back the whole calling
            // transaction -- we'd rather fail loudly than silently lose an event.
            throw new IllegalStateException("Failed to serialize outbox event", e);
        }
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(), order.getCustomerId(), order.getProductId(), order.getQuantity(),
                order.getStatus().name(), order.getCreatedAt());
    }
}
