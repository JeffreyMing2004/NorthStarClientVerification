package com.ming.northstarclientverification.verification;

/**
 * 验证被远端明确拒绝时抛出的异常。
 *
 * <p>异常信息会原样出现在 crash-reports 崩溃报告中，便于玩家和管理员定位。</p>
 *
 * <p>只记录「QQ + 游戏 ID」两项身份信息：离线模式下 UUID 不稳定，记录下来没有意义。</p>
 */
public class VerificationRejectedException extends RuntimeException {

    private final String playerName;
    private final String qq;
    private final String url;
    private final int httpCode;
    private final String responseBody;

    public VerificationRejectedException(String message, String playerName, String qq,
                                         String url, int httpCode, String responseBody) {
        super(message);
        this.playerName = playerName;
        this.qq = qq;
        this.url = url;
        this.httpCode = httpCode;
        this.responseBody = responseBody;
    }

    /** 游戏 ID。 */
    public String getPlayerName() {
        return playerName;
    }

    public String getQq() {
        return qq;
    }

    public String getUrl() {
        return url;
    }

    public int getHttpCode() {
        return httpCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
