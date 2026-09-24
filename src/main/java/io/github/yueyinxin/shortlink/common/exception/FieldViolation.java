package io.github.yueyinxin.shortlink.common.exception;

/**
 * 字段级错误明细。
 *
 * @param field  出错的字段名（与请求体中的 JSON 字段名一致）
 * @param reason 人类可读的原因
 */
public record FieldViolation(String field, String reason) {
}
