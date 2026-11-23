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

## 주문 요청의 멱등성

`POST /api/drops/{dropId}/orders`는 `Idempotency-Key` 헤더가 필수입니다. 키는 회원별로 구분됩니다.

- 같은 키, 같은 내용으로 다시 보내면 새 주문을 만들지 않고 처음 응답(201과 같은 본문)을 그대로 돌려줍니다.
- 같은 키를 다른 수량으로 보내면 `IDEMPOTENCY_KEY_REUSED`(422)입니다.
- 앞선 요청이 실패(품절, 한도 초과 등)했다면 키도 함께 롤백되므로, 같은 키로 다시 시도할 수 있습니다.
