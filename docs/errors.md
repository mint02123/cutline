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
