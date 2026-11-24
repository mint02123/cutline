package com.cutline.order.api;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.common.web.CurrentMember;
import com.cutline.order.MyOrdersQuery;
import com.cutline.order.Order;
import com.cutline.order.OrderCancelService;
import com.cutline.order.OrderPlacementService;
import com.cutline.order.OrderReceipt;
import com.cutline.order.OrderRepository;
import com.cutline.order.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderPlacementService placement;
    private final OrderRepository orders;
    private final OrderCancelService cancels;
    private final MyOrdersQuery myOrders;

    public OrderController(OrderPlacementService placement, OrderRepository orders, OrderCancelService cancels,
                           MyOrdersQuery myOrders) {
        this.placement = placement;
        this.orders = orders;
        this.cancels = cancels;
        this.myOrders = myOrders;
    }

    @PostMapping("/api/drops/{dropId}/orders")
    ResponseEntity<OrderReceipt> place(@CurrentMember Long memberId,
                                       @PathVariable Long dropId,
                                       @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                       @Valid @RequestBody PlaceOrderRequest request) {
        OrderReceipt receipt = placement.place(memberId, dropId, request.qty(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/orders/" + receipt.orderId())).body(receipt);
    }

    @GetMapping("/api/orders/{orderId}")
    OrderResponse get(@CurrentMember Long memberId, @PathVariable Long orderId) {
        Order order = orders.findById(orderId)
                .filter(o -> o.getMemberId().equals(memberId))
                .orElseThrow(() -> new CutlineException(ErrorCode.ORDER_NOT_FOUND));
        return new OrderResponse(order.getId(), order.getDropId(), order.getQty(), order.getAmount(),
                order.getStatus(), order.getHoldExpiresAt());
    }

    /** 구매자 취소. 결제 전이면 점유 해제, 결제 후면 환불. 이미 취소·환불된 주문에 다시 보내도 200. */
    @PostMapping("/api/orders/{orderId}/cancel")
    CancelResponse cancel(@CurrentMember Long memberId, @PathVariable Long orderId) {
        return new CancelResponse(orderId, cancels.cancel(memberId, orderId));
    }

    @GetMapping("/api/me/orders")
    MyOrdersQuery.Page myOrders(@CurrentMember Long memberId,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return myOrders.find(memberId, cursor, size);
    }

    public record CancelResponse(long orderId, OrderStatus status) {
    }

    public record PlaceOrderRequest(@Min(1) @Max(100) int qty) {
    }

    public record OrderResponse(Long orderId, Long dropId, int qty, long amount, OrderStatus status, Instant holdExpiresAt) {
    }
}
