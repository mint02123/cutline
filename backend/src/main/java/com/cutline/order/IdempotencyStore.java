package com.cutline.order;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 멱등 키 저장소 (ADR-004).
 * 키 등록은 주문과 같은 트랜잭션에서 한다. 같은 키로 동시에 들어온 요청은 PK 잠금에서 기다렸다가,
 * 앞선 요청이 커밋되면 저장된 응답을 받고, 롤백되면 처음부터 다시 처리한다.
 */
@Component
class IdempotencyStore {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    IdempotencyStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 새 키면 등록하고 빈 값을, 이미 완료된 키면 저장된 응답을 돌려준다.
     */
    Optional<OrderReceipt> begin(Long memberId, String key, String requestHash, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO idempotency_key (member_id, idem_key, request_hash, status, created_at)
                    VALUES (?, ?, ?, 'IN_PROGRESS', ?)""", memberId, key, requestHash, Timestamp.from(now));
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            return replay(memberId, key, requestHash);
        }
    }

    void complete(Long memberId, String key, int responseStatus, OrderReceipt receipt) {
        jdbc.update("""
                UPDATE idempotency_key SET status = 'COMPLETED', response_status = ?, response_body = ?
                WHERE member_id = ? AND idem_key = ?""",
                responseStatus, json.writeValueAsString(receipt), memberId, key);
    }

    private Optional<OrderReceipt> replay(Long memberId, String key, String requestHash) {
        var rows = jdbc.queryForList(
                "SELECT request_hash, status, response_body FROM idempotency_key WHERE member_id = ? AND idem_key = ?",
                memberId, key);
        if (rows.isEmpty()) {
            // 앞선 요청이 롤백되어 키가 사라진 경우. 클라이언트가 다시 보내면 새로 처리된다.
            throw new CutlineException(ErrorCode.REQUEST_IN_PROGRESS);
        }
        var row = rows.getFirst();
        if (!requestHash.equals(row.get("request_hash"))) {
            throw new CutlineException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (!"COMPLETED".equals(row.get("status"))) {
            throw new CutlineException(ErrorCode.REQUEST_IN_PROGRESS);
        }
        return Optional.of(json.readValue(String.valueOf(row.get("response_body")), OrderReceipt.class));
    }
}
