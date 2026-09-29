package com.bpm.core.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CallerId} 的解析：身分只來自 SecurityContext。
 *
 * <h2>為什麼不 fallback 到標頭</h2>
 *
 * <p>「SecurityContext 沒有身分時退回讀 X-User-Id」看起來很方便，
 * 但那會讓整套認證變成裝飾 —— 任何一條沒被授權規則涵蓋的路徑，
 * 身分就退回可偽造的標頭，而且完全沒有痕跡。
 *
 * <p>所以未認證時回 {@code null}。授權矩陣的最後一條是 {@code denyAll()}，
 * 正常情況下到不了 controller；真的到了（例如將來新增的公開端點），
 * null 會讓稽核記成 unknown 而不是一個編造的身分。
 */
@Component
public class CallerIdArgumentResolver implements HandlerMethodArgumentResolver {

    /** 未認證時稽核裡記這個值，而不是 null 或編造的身分。 */
    public static final String UNKNOWN = "unknown";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CallerId.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return currentCallerId();
    }

    /**
     * 目前的呼叫者，未認證時回 {@code null}。
     *
     * <p>{@code anonymousUser} 是 Spring Security 給未認證請求的 principal
     * 名稱，不是真實身分 —— 必須過濾掉，否則稽核裡會出現一個
     * 看起來像使用者 ID 的假身分。
     */
    public static String currentCallerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        String name = auth.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) return null;
        return name;
    }

    /** 稽核用：未認證時回 {@link #UNKNOWN} 而非 null。 */
    public static String currentCallerIdOrUnknown() {
        String id = currentCallerId();
        return id != null ? id : UNKNOWN;
    }
}
