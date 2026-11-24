package com.cutline.payment.api;

import com.cutline.common.web.CurrentMember;
import com.cutline.payment.PaymentResult;
import com.cutline.payment.PaymentService;
import com.cutline.payment.PaymentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 확정. 클라이언트가 PG 결제창에서 받은 paymentKey와 금액을 보내면 서버가 PG에 승인을 요청한다.
 * 주문당 결제는 하나뿐이라(order_id UNIQUE) 같은 요청을 다시 보내도 이중 결제가 되지 않는다.
 */
@RestController
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** 200: 결제 완료, 202: PG 응답을 받지 못해 확인 중 (GET /api/orders/{id}로 결과 확인). */
    @PostMapping("/api/orders/{orderId}/payments")
    ResponseEntity<PaymentResult> confirm(@CurrentMember Long memberId, @PathVariable long orderId,
                                          @Valid @RequestBody ConfirmRequest request) {
        PaymentResult result = payments.confirm(memberId, orderId, request.paymentKey(), request.amount());
        HttpStatus status = result.paymentStatus() == PaymentStatus.UNKNOWN ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    public record ConfirmRequest(@NotBlank @Size(max = 200) String paymentKey, @Positive long amount) {
    }
}
