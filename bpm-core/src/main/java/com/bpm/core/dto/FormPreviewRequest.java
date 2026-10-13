package com.bpm.core.dto;

/**
 * {@code POST /api/forms/preview} 的請求
 * （docs/plan/2026-10-12-uniform-form-refactor.md §五 A4）。
 *
 * <p>body 是一個具名欄位而不是裸字串：DSL 是 JSON 物件，裸字串會讓
 * 「這是 JSON 還是被序列化兩次的 JSON」變成一個每次都要猜的問題，
 * 而且錯誤訊息沒有欄位名可以指名。
 *
 * @param schemaJson 未儲存的 uni-form DSL 字串；必須先過 DSL schema ＋
 *                   {@code compileStatic}，否則 400
 */
public record FormPreviewRequest(String schemaJson) {
}
