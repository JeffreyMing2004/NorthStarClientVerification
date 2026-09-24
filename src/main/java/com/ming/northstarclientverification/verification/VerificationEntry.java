package com.ming.northstarclientverification.verification;

/**
 * 单条验证记录，对应 verification.json 中 players 下的一个条目。
 *
 * <p>字段名即 JSON 键名，由 Gson 反射读写，请勿随意改名。</p>
 *
 * <p><b>不再保存 UUID</b>：离线模式下 UUID 不稳定，身份只由游戏 ID 确定，
 * 因此 players 的键就是游戏 ID（小写）。旧版本写入的 {@code uuid} 字段会被
 * Gson 自动忽略。</p>
 */
public class VerificationEntry {

    /** 玩家名（游戏 ID），也是本条记录在 players 里的键。 */
    private String name;
    /** 已录入的 QQ 号。 */
    private String qq;
    /** 首次验证时间（epoch 毫秒）。 */
    private long firstVerifiedAt;
    /** 最近一次验证时间（epoch 毫秒）。 */
    private long lastVerifiedAt;

    /** Gson 反序列化需要无参构造。 */
    public VerificationEntry() {
    }

    public VerificationEntry(String name, String qq, long now) {
        this.name = name;
        this.qq = qq;
        this.firstVerifiedAt = now;
        this.lastVerifiedAt = now;
    }

    /** 用新的信息刷新这条记录（保留首次验证时间）。 */
    void update(String name, String qq, long now) {
        this.name = name;
        this.qq = qq;
        if (this.firstVerifiedAt <= 0L) {
            this.firstVerifiedAt = now;
        }
        this.lastVerifiedAt = now;
    }

    public String getName() {
        return name;
    }

    public String getQq() {
        return qq;
    }

    public long getFirstVerifiedAt() {
        return firstVerifiedAt;
    }

    public long getLastVerifiedAt() {
        return lastVerifiedAt;
    }
}
