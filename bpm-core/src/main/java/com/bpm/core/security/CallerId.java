package com.bpm.core.security;

import com.bpm.core.security.CallerId;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 已認證的呼叫者識別。
 *
 * <h2>為什麼要有這個註解，而不是直接注入 Authentication</h2>
 *
 * <p>改動前 32 處 controller 參數寫的是
 * {@code @CallerId String operatorId} —— 也就是
 * <b>呼叫端自稱的身分</b>。那個字串同時被用於授權判斷與稽核的 operatorId。
 *
 * <p>換成這個註解有兩個作用：
 * <ol>
 *   <li>語意改變：參數名沒變，但它現在代表「已認證的呼叫者」而非
 *       「請求裡的某個標頭」。</li>
 *   <li>收斂：身分的推導只有 {@link CallerIdArgumentResolver} 一處。
 *       controller 不再有機會「順手」讀回裸標頭 ——
 *       而那正是這類缺陷復發的典型方式。</li>
 * </ol>
 *
 * <p>直接注入 {@code Authentication} 也可行，但每個 controller 都要自己寫
 * {@code auth.getName()} 與 null 處理，於是「呼叫者是誰」的定義又散成 32 份。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CallerId {
}
