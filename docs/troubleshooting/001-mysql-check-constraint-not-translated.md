# 001. MySQL CHECK 제약 위반이 DataIntegrityViolationException으로 잡히지 않음

- 날짜: 2026-11-24
- 단계: 1단계 1일차 (스키마 제약 테스트)

## 증상
재고가 0 아래로 내려가는 UPDATE를 막기 위해 `drop_event`에 `CHECK (remaining_qty >= 0)`을 걸었다.
제약은 정상 동작해 UPDATE가 거절됐지만, 테스트에서 기대한 `DataIntegrityViolationException`이 아니라
`UncategorizedSQLException`(SQL state `HY000`, error code `3819`)이 던져졌다.

## 원인
Spring은 DB 에러 코드를 `sql-error-codes.xml`로 예외 타입에 매핑한다.
spring-jdbc 7.0.11의 기본 MySQL 매핑에는 3819(`ER_CHECK_CONSTRAINT_VIOLATED`, MySQL 8.0.16+)가 없다.
그래서 정합성 위반인데도 "분류되지 않은 예외"로 떨어진다.

## 왜 중요한가
주문 서비스는 정합성 위반(품절, 한도 초과)을 `DataIntegrityViolationException` 계열로 잡아 409로 응답할 계획이다.
분류가 안 되면 정합성 위반이 500으로 나가고, 장애 주입 테스트에서 "정상적인 거절"과 "진짜 장애"를 구분할 수 없다.

## 해결
`backend/src/main/resources/sql-error-codes.xml`에서 MySQL 매핑을 덮어쓰고
`dataIntegrityViolationCodes`에 3819를 추가했다. 나머지 값은 기본 설정 그대로 복사했다.

## 검증
`SchemaConstraintsTest`의 재고 하한·상한, 주문 상태 CHECK 테스트 3개가 실패에서 통과로 바뀌었다.

## 남은 과제
- 주문 로직에서는 CHECK 위반에 기대지 않고 조건부 UPDATE(`remaining_qty >= :q`)의 영향 행 수로 품절을 판단한다. CHECK는 코드 버그에 대한 마지막 방어선이다.
- Spring 버전을 올릴 때 기본 매핑에 3819가 추가됐는지 확인하고, 추가됐다면 이 파일을 지운다.
