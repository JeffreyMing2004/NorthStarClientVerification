package com.ming.northstarclientverification;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.regex.Pattern;

/**
 * Mod 配置项，生成于 {@code config/northstarclientverification-common.toml}。
 */
@Mod.EventBusSubscriber(modid = Northstarclientverification.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Config {

    /** QQ 号默认规则：5-11 位数字，且不能以 0 开头。 */
    public static final String DEFAULT_QQ_PATTERN = "^[1-9]\\d{4,10}$";

    /** 开发环境验证接口：本机后端（Spring Boot 默认 8080）。 */
    public static final String VERIFY_URL_DEV = "http://127.0.0.1:8080/api/beta/verify";

    /** 生产环境验证接口：站点与接口同域。 */
    public static final String VERIFY_URL_PROD = "https://northstar.mingpixel.net/api/beta/verify";

    /**
     * 当前默认接口地址。
     *
     * <p>自 v3.7 起正式对接<b>生产环境</b>：生产后端与前端已部署上线，
     * 且 {@code /api/beta/verify} 已确认为匿名可访问（实测 200 + 业务 JSON）。</p>
     *
     * <p>开发调试时不必改代码，直接改
     * {@code config/northstarclientverification-common.toml} 里的 {@code verifyUrl}
     * 为 {@link #VERIFY_URL_DEV} 即可（该文件在实例目录下，优先级高于此处默认值）。</p>
     */
    public static final String DEFAULT_VERIFY_URL = VERIFY_URL_PROD;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue ENABLE_VERIFICATION = BUILDER
            .comment("是否在主界面弹出 QQ 内测验证窗口")
            .define("enableVerification", true);

    private static final ForgeConfigSpec.ConfigValue<String> VERIFY_URL = BUILDER
            .comment("远端验证接口完整地址（GET）。请求会附加 qq / name 两个查询参数，",
                     "name 为自动识别到的游戏ID（离线模式即登录用户名）。",
                     "例如配置 https://example.com/api/northstar/verify，实际请求为",
                     "https://example.com/api/northstar/verify?qq=123456789&name=Steve",
                     "离线模式下不提供 UUID：各启动器生成的 UUID 每次可能不同，无法作为身份依据。",
                     "",
                     "当前默认值：生产环境 " + VERIFY_URL_PROD,
                     "",
                     "开发调试请改为开发环境 " + VERIFY_URL_DEV,
                     "　开发环境 = 本机后端，需先在本机启动 northstar_backend（默认 8080）。",
                     "  若客户端与后端不在同一台机器，请把 127.0.0.1 换成后端所在机器的地址。",
                     "  ⚠ 127.0.0.1 只对本机有效，分发给玩家时必须用生产地址。",
                     "",
                     "留空表示不做远端校验，只写入本地 verification.json（会直接放行）。")
            .define("verifyUrl", DEFAULT_VERIFY_URL);

    private static final ForgeConfigSpec.BooleanValue REVERIFY_ON_LAUNCH = BUILDER
            .comment("本地已有验证记录时，是否仍然弹窗重新走一次远端校验")
            .define("reverifyOnLaunch", false);

    private static final ForgeConfigSpec.IntValue HTTP_TIMEOUT_MS = BUILDER
            .comment("远端验证请求超时时间（毫秒）")
            .defineInRange("httpTimeoutMs", 8000, 1000, 60000);

    private static final ForgeConfigSpec.IntValue OPEN_DELAY_TICKS = BUILDER
            .comment("主界面出现后延迟多少刻再弹出验证窗口，20 刻 = 1 秒")
            .defineInRange("openDelayTicks", 20, 0, 200);

    private static final ForgeConfigSpec.BooleanValue BLOCK_ESCAPE = BUILDER
            .comment("验证完成前是否禁止用 ESC 关闭验证窗口（仍可通过\"退出游戏\"按钮离开）")
            .define("blockEscape", true);

    private static final ForgeConfigSpec.IntValue MAX_ATTEMPTS = BUILDER
            .comment("允许验证失败几次，达到该次数后才会崩溃退出（默认 3 次）。",
                     "只有\"明确未通过\"（接口返回 2xx 且 success=false）才计数；",
                     "网络超时、HTTP 4xx/5xx、响应体无法解析都按\"服务不可用\"处理，",
                     "停在界面让玩家重试，且不消耗机会。",
                     "",
                     "计数按游戏ID持久化在 config/northstar/verification.json 里，",
                     "重启游戏不会重置；验证通过后自动清零。",
                     "设为 1 即恢复\"一次未通过就崩\"的旧行为。")
            .defineInRange("maxAttempts", 3, 1, 100);

    private static final ForgeConfigSpec.BooleanValue CRASH_WHEN_EXHAUSTED = BUILDER
            .comment("机会用尽后是否生成崩溃报告并退出游戏。",
                     "设为 false 时永不崩溃，只会停在验证界面提示剩余次数（调试用）。")
            .define("crashWhenExhausted", true);

    private static final ForgeConfigSpec.ConfigValue<String> QQ_PATTERN = BUILDER
            .comment("QQ 号校验正则，默认 5-11 位数字且不以 0 开头")
            .define("qqPattern", DEFAULT_QQ_PATTERN);

    private static final ForgeConfigSpec.BooleanValue DEBUG_LOG = BUILDER
            .comment("是否输出调试日志")
            .define("debugLog", false);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    // ---- 运行时字段（配置加载时同步） ----
    public static boolean enableVerification = true;
    public static String verifyUrl = DEFAULT_VERIFY_URL;
    public static boolean reverifyOnLaunch = false;
    public static int httpTimeoutMs = 8000;
    public static int openDelayTicks = 20;
    public static boolean blockEscape = true;
    /** 允许的「明确未通过」次数，达到后崩溃退出。 */
    public static int maxAttempts = 3;
    /** 机会用尽后是否崩溃；false 表示只提示、永不崩溃。 */
    public static boolean crashWhenExhausted = true;
    public static boolean debugLog = false;
    /** 已编译的 QQ 号校验正则。 */
    public static Pattern qqRegex = Pattern.compile(DEFAULT_QQ_PATTERN);

    private Config() {
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (!Northstarclientverification.MODID.equals(event.getConfig().getModId())) {
            return;
        }

        enableVerification = ENABLE_VERIFICATION.get();
        String url = VERIFY_URL.get();
        verifyUrl = url == null ? "" : url.trim();
        reverifyOnLaunch = REVERIFY_ON_LAUNCH.get();
        httpTimeoutMs = HTTP_TIMEOUT_MS.get();
        openDelayTicks = OPEN_DELAY_TICKS.get();
        blockEscape = BLOCK_ESCAPE.get();
        maxAttempts = MAX_ATTEMPTS.get();
        crashWhenExhausted = CRASH_WHEN_EXHAUSTED.get();
        debugLog = DEBUG_LOG.get();

        String pattern = QQ_PATTERN.get();
        try {
            qqRegex = Pattern.compile(pattern);
        } catch (Exception e) {
            Northstarclientverification.LOGGER.warn("[NorthStar] 配置中的 qqPattern 非法：{}，回退到默认规则", pattern);
            qqRegex = Pattern.compile(DEFAULT_QQ_PATTERN);
        }

        Northstarclientverification.LOGGER.info(
                "[NorthStar] 配置已加载：enableVerification={}, verifyUrl={}, httpTimeoutMs={}, maxAttempts={}, crashWhenExhausted={}",
                enableVerification, verifyUrl.isEmpty() ? "(未配置)" : verifyUrl, httpTimeoutMs,
                maxAttempts, crashWhenExhausted);
    }
}
