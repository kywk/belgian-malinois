package com.bpm.core.dto;

import java.util.List;

/**
 * 快取失效請求。
 *
 * @param type      {@code manager} / {@code substitute} / {@code department} / {@code all}。
 *                  null 或空白代表 all。未知的值會回 400 —— 這個參數改動前
 *                  完全沒有被讀取（security-audit P2-8）。
 * @param userIds   要失效的使用者。
 * @param permCodes 要失效的權限碼（僅 /perm 端點使用）。
 * @param deptIds   要失效成員清單的部門。
 *                  <p>⚠️ 使用者異動部門時，{@code userIds} 只能讓後端推導出
 *                  他<b>離開</b>的那個部門（從快取裡的舊值）。<b>加入</b>的新部門
 *                  後端無從得知，必須在這裡明確指定，否則新部門的成員清單會
 *                  漏掉這個人，最長 30 分鐘。
 */
public record CacheInvalidateRequest(
        String type,
        List<String> userIds,
        List<String> permCodes,
        List<String> deptIds
) {}
