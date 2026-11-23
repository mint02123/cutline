package com.cutline.common.web;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class CurrentMemberResolver implements HandlerMethodArgumentResolver {

    static final String HEADER = "X-Member-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentMember.class) && parameter.getParameterType() == Long.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String value = webRequest.getHeader(HEADER);
        if (value == null || value.isBlank()) {
            throw new CutlineException(ErrorCode.MEMBER_REQUIRED);
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new CutlineException(ErrorCode.MEMBER_REQUIRED);
        }
    }
}
