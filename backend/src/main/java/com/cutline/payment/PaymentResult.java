package com.cutline.payment;

import com.cutline.order.OrderStatus;

public record PaymentResult(long orderId, OrderStatus orderStatus, PaymentStatus paymentStatus) {
}
