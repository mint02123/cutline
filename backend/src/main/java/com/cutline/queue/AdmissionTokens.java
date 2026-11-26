package com.cutline.queue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 입장 토큰: {@code base64url(dropId:memberId:expiresAtMillis).base64url(HMAC-SHA256)}.
 *
 * <p>서명 토큰이라 검증에 Redis가 필요 없다. Redis가 내려가도 이미 입장한 사람은 주문할 수 있고,
 * 대기열을 우회해 발급한 토큰도 같은 방식으로 검증된다 (ADR-012). 회원 id가 서명에 들어가므로 다른 회원의 토큰으로는
 * 주문할 수 없다. 같은 회원이 만료 전에 토큰을 다시 쓰는 것은 막지 않는다. 그 회원이 살 수 있는 양은 MySQL 1인 한도가 정한다.
 */
@Component
public class AdmissionTokens {

    public enum Verdict { VALID, EXPIRED, INVALID }

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    AdmissionTokens(@Value("${cutline.queue.admission.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("cutline.queue.admission.secret must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    String issue(long dropId, long memberId, long expiresAtMillis) {
        String payload = ENCODER.encodeToString(
                (dropId + ":" + memberId + ":" + expiresAtMillis).getBytes(StandardCharsets.US_ASCII));
        return payload + "." + ENCODER.encodeToString(sign(payload));
    }

    public Verdict verify(String token, long dropId, long memberId, long nowMillis) {
        if (token == null) {
            return Verdict.INVALID;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Verdict.INVALID;
        }
        String payload = token.substring(0, dot);
        try {
            byte[] given = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sign(payload), given)) {
                return Verdict.INVALID;
            }
            String[] parts = new String(DECODER.decode(payload), StandardCharsets.US_ASCII).split(":");
            if (parts.length != 3 || Long.parseLong(parts[0]) != dropId || Long.parseLong(parts[1]) != memberId) {
                return Verdict.INVALID;
            }
            return Long.parseLong(parts[2]) > nowMillis ? Verdict.VALID : Verdict.EXPIRED;
        } catch (IllegalArgumentException e) {
            // 잘못된 base64, 숫자가 아닌 필드
            return Verdict.INVALID;
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
