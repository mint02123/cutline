package com.cutline.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 요청한 회원 ID를 주입한다.
 * 1단계 임시 구현: {@code X-Member-Id} 헤더를 그대로 믿는다. JWT 인증으로 교체할 때 이 리졸버만 바꾼다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentMember {
}
