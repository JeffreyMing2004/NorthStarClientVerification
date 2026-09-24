package com.ming.northstarclientverification.verification;

/**
 * 「还有几次机会 / 何时才崩溃」的判定规则。
 *
 * <p>刻意做成无 Minecraft / Forge 依赖的纯静态方法，这样它可以在裸 JVM 的联调自测里
 * 直接断言边界（第 2 次 vs 第 3 次、{@code maxAttempts=1} 等），不必启动游戏。</p>
 *
 * <p><b>语义</b>：{@code usedAttempts} 是<b>包含本次</b>在内的累计「明确未通过」次数。
 * 只有<u>明确未通过</u>（接口返回 {@code success=false}）才计数；网络异常、超时、
 * HTTP 4xx/5xx 属于「服务不可用」，不消耗机会。</p>
 */
public final class AttemptPolicy {

    /** 一次「明确未通过」之后该怎么办。 */
    public enum Decision {
        /** 还有机会，停在验证界面让玩家重试。 */
        RETRY,
        /** 机会已用尽，生成崩溃报告并退出游戏。 */
        CRASH
    }

    private AttemptPolicy() {
    }

    /**
     * @param usedAttempts        含本次在内的累计失败次数
     * @param maxAttempts         允许的失败次数上限
     * @param crashWhenExhausted  机会用尽后是否崩溃（false 表示永不崩溃，仅提示）
     */
    public static Decision decide(int usedAttempts, int maxAttempts, boolean crashWhenExhausted) {
        if (!crashWhenExhausted) {
            return Decision.RETRY;
        }
        return isExhausted(usedAttempts, maxAttempts) ? Decision.CRASH : Decision.RETRY;
    }

    /** 机会是否已用尽。即使已用尽也仍允许玩家再试——填对了照样能过。 */
    public static boolean isExhausted(int usedAttempts, int maxAttempts) {
        return clampUsed(usedAttempts) >= normalizedLimit(maxAttempts);
    }

    /** 剩余机会数，已用尽时为 0。 */
    public static int remaining(int usedAttempts, int maxAttempts) {
        return Math.max(0, normalizedLimit(maxAttempts) - clampUsed(usedAttempts));
    }

    /** 上限至少为 1，避免把 0 / 负数配成「一次都不许试」。 */
    private static int normalizedLimit(int maxAttempts) {
        return Math.max(1, maxAttempts);
    }

    private static int clampUsed(int usedAttempts) {
        return Math.max(0, usedAttempts);
    }
}
