package com.ming.northstarclientverification.verification;

/**
 * 一次远端验证的结果。
 *
 * @param status   结果状态
 * @param httpCode HTTP 状态码，未拿到响应时为 -1
 * @param body     响应体原文（已截断），可能为 null
 * @param url      实际请求的完整 URL
 * @param error    出错说明，仅 {@link Status#ERROR} 时有值
 */
public record VerifyResult(Status status, int httpCode, String body, String url, String error) {

    public enum Status {
        /** 远端判定通过。 */
        SUCCESS,
        /** 远端明确判定未通过（HTTP 2xx 且 success=false / code!=0）。 */
        REJECTED,
        /** 服务不可用：网络异常、超时、非 2xx、响应体无法解析。可让玩家重试。 */
        ERROR,
        /** 未配置 verifyUrl，跳过远端校验。 */
        SKIPPED
    }

    public static VerifyResult success(int httpCode, String body, String url) {
        return new VerifyResult(Status.SUCCESS, httpCode, body, url, null);
    }

    public static VerifyResult rejected(int httpCode, String body, String url) {
        return new VerifyResult(Status.REJECTED, httpCode, body, url, null);
    }

    public static VerifyResult error(String url, int httpCode, String error) {
        return new VerifyResult(Status.ERROR, httpCode, null, url, error);
    }

    public static VerifyResult skipped(String url) {
        return new VerifyResult(Status.SKIPPED, -1, null, url, null);
    }

    /** 是否允许玩家继续（通过，或未配置接口时的本地放行）。 */
    public boolean isPassed() {
        return status == Status.SUCCESS || status == Status.SKIPPED;
    }
}
