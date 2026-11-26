# 에러 코드

모든 에러는 [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) 형식(`application/problem+json`)으로 응답합니다.
클라이언트는 `code` 필드로 분기합니다. 입력값 검증 실패는 `errors` 배열에 필드별 사유가 들어갑니다.

```json
{
  "type": "about:blank",
  "title": "요청 값이 올바르지 않습니다.",
  "status": 400,
  "detail": "입력값을 확인해 주세요.",
  "code": "INVALID_REQUEST",
  "errors": [{ "field": "totalQty", "message": "1 이상이어야 합니다" }]
}
```

| code | HTTP | 의미 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | 입력값 검증 실패, 과거 오픈 시각, 총수량보다 큰 1인 한도 |
| `SELLER_NOT_FOUND` | 404 | 판매자 없음 |
| `PRODUCT_NOT_FOUND` | 404 | 상품 없음 |
| `DROP_NOT_FOUND` | 404 | 드롭 없음 |
| `PRODUCT_NOT_OWNED` | 403 | 다른 판매자의 상품으로 드롭 생성 시도 |
| `MEMBER_REQUIRED` | 401 | `X-Member-Id` 헤더 없음 (임시 인증, JWT로 교체 예정) |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 주문 요청에 `Idempotency-Key` 헤더가 없거나 100자를 넘음 |
| `IDEMPOTENCY_KEY_REUSED` | 422 | 같은 키로 내용이 다른 요청을 보냄 |
| `REQUEST_IN_PROGRESS` | 409 | 같은 키의 앞선 요청이 아직 처리 중 |
| `DROP_NOT_OPEN` | 409 | 오픈 전이거나 종료된 드롭 |
| `SOLD_OUT` | 409 | 남은 재고보다 많이 주문 |
| `PURCHASE_LIMIT_EXCEEDED` | 409 | 1인 구매 한도 초과 (이미 산 수량 포함) |
| `ORDER_NOT_FOUND` | 404 | 주문 없음, 또는 다른 회원의 주문 |
| `ORDER_NOT_PAYABLE` | 409 | 취소·환불된 주문에 결제 시도 |
| `ORDER_NOT_CANCELABLE` | 409 | 만료된 주문 취소 시도 |
| `HOLD_EXPIRED` | 409 | 결제 대기 시간이 지남. PG 승인 도중 만료됐다면 승인을 취소(환불)한 뒤 이 코드로 응답 |
| `PAYMENT_AMOUNT_MISMATCH` | 400 | 결제 요청 금액이 주문 금액과 다름 (PG를 부르기 전에 거절) |
| `PAYMENT_IN_PROGRESS` | 409 | 같은 주문의 결제가 처리 중이거나 결과 확인 중 |
| `PAYMENT_DECLINED` | 422 | PG가 거절. 점유 시간 안이면 다른 결제수단으로 다시 시도 가능 |
| `TOO_BUSY` | 503 | Redis 관문 장애 중 한 서버의 MySQL 경로 동시 처리 한도를 넘음. 잠시 뒤 같은 멱등 키로 다시 시도 |
| `ADMISSION_REQUIRED` | 403 | 주문 요청에 입장 토큰(`X-Admission-Token`)이 없거나, 다른 회원·다른 드롭의 토큰이거나, 서명이 맞지 않음 |
| `ADMISSION_EXPIRED` | 403 | 입장 후 주문 가능 시간(2분)이 지남. 다시 줄을 서야 함 |
| `QUEUE_UNAVAILABLE` | 503 | 대기열(Redis)에 닿지 못하고 우회도 꺼져 있음(`cutline.queue.bypass-on-outage=false`). 기본 설정에서는 나오지 않음 |

## 주문 요청의 멱등성

`POST /api/drops/{dropId}/orders`는 `Idempotency-Key` 헤더가 필수입니다. 키는 회원별로 구분됩니다.

- 같은 키, 같은 내용으로 다시 보내면 새 주문을 만들지 않고 처음 응답(201과 같은 본문)을 그대로 돌려줍니다.
- 같은 키를 다른 수량으로 보내면 `IDEMPOTENCY_KEY_REUSED`(422)입니다.
- 앞선 요청이 실패(품절, 한도 초과 등)했다면 키도 함께 롤백되므로, 같은 키로 다시 시도할 수 있습니다.

## 대기열

`POST /api/drops/{dropId}/queue`로 줄을 서고, `GET /api/drops/{dropId}/queue/me`로 순번을 묻습니다. 둘 다 같은 본문을 돌려줍니다.

```json
{ "dropId": 7, "state": "WAITING", "ahead": 312, "lineLength": 2950, "estimatedWaitSeconds": 2, "pollAfterMs": 1000,
  "admissionToken": null, "admissionExpiresAt": null, "bypassed": false }
```

| `state` | 의미 | 클라이언트 동작 |
| --- | --- | --- |
| `WAITING` | 줄에서 기다리는 중. `ahead`는 내 앞 인원(0이면 맨 앞) | `pollAfterMs` 뒤 다시 조회 (1~5초, 입장이 가까울수록 짧음) |
| `ADMITTED` | 입장함 | `admissionToken`을 주문 요청의 `X-Admission-Token` 헤더에 넣어 `admissionExpiresAt` 전에 주문 |
| `SOLD_OUT` | 줄에 서 있지만 매진 | 품절 안내. 만료·취소로 재고가 돌아오면 같은 자리에서 다시 `WAITING` |
| `EXPIRED` | 입장했지만 주문하지 않고 입장 시간이 지남 | 다시 줄 서기 (맨 뒤에 섬) |
| `NOT_IN_QUEUE` | 줄에 서지 않음 | 줄 서기 |

- 이미 줄에 서 있거나 입장한 회원이 다시 줄 서기를 보내면 지금 상태를 돌려줍니다. 여러 탭에서 눌러도 자리는 하나입니다.
- 순번은 Redis에 도착한 순서로 정해지고, 입장은 서버 전체 기준 초당 200명(설정값)씩 앞에서부터 이루어집니다.
- 오픈 전이면 409 `DROP_NOT_OPEN`, 매진이면 409 `SOLD_OUT`으로 줄에 세우지 않습니다.
- 대기열(Redis)에 닿지 못하면 줄을 건너뛰고 바로 `ADMITTED`, `bypassed: true`로 답합니다 (ADR-012). 이때 주문은 느린 MySQL 경로로 처리되며, 몰리면 503 `TOO_BUSY`가 날 수 있습니다.

## 결제 확정 응답

`POST /api/orders/{orderId}/payments` (`{"paymentKey": "...", "amount": 219000}`)

| 응답 | 의미 | 클라이언트 동작 |
| --- | --- | --- |
| 200 `PAID/APPROVED` | 결제 완료 | 완료 화면 |
| 202 `PENDING_PAYMENT/UNKNOWN` | PG 응답을 받지 못해 확인 중. 이 동안 주문은 만료되지 않는다 | `GET /api/orders/{id}`로 상태 확인 |
| 409 `HOLD_EXPIRED` | 시간 초과. 승인됐다면 취소(환불)됨 | 다시 주문 |
| 422 `PAYMENT_DECLINED` | 거절 | 다른 결제수단으로 재시도 |

주문당 결제는 하나뿐이라(`payment.order_id` UNIQUE) 같은 요청을 다시 보내도 이중 결제가 되지 않습니다.
이미 결제된 주문에 다시 보내면 PG를 부르지 않고 200을 돌려줍니다.

## 주문 취소

`POST /api/orders/{orderId}/cancel`

| 주문 상태 | 결과 |
| --- | --- |
| 결제 전 (`PENDING_PAYMENT`) | `CANCELED`, 재고·구매 한도 복구. PG 승인이 진행 중이었다면 그 승인은 자동 취소 |
| 결제 후 (`PAID`) | `REFUNDED`, 재고·구매 한도 복구, PG 환불 (실패하면 복구 작업이 재시도) |
| 이미 `CANCELED`/`REFUNDED` | 같은 상태로 200 (멱등) |
| `EXPIRED` | 409 `ORDER_NOT_CANCELABLE` |

## 내 주문 목록

`GET /api/me/orders?size=20&cursor=...` (size 1~100)

최신순이며, 응답의 `nextCursor`를 다음 요청의 `cursor`로 넘깁니다. 마지막 페이지면 `nextCursor`가 `null`입니다.
