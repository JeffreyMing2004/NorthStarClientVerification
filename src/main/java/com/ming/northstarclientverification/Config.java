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
                     "留空表示不做远端校验，只写入本地 verification.json")
            .define("verifyUrl", "");

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

    private static final ForgeConfigSpec.BooleanValue CRASH_ON_REJECT = BUILDER
            .comment("远端明确判定\"验证未通过\"时，是否生成崩溃报告并退出游戏。",
                     "只有接口返回 2xx 且 success=false（或 code!=0）才算\"未通过\"；",
                     "网络超时、HTTP 4xx/5xx、响应体无法解析都按\"服务不可用\"处理，停在界面让玩家重试。")
            .define("crashOnReject", true);

    private static final ForgeConfigSpec.ConfigValue<String> QQ_PATTERN = BUILDER
            .comment("QQ 号校验正则，默认 5-11 位数字且不以 0 开头")
            .define("qqPattern", DEFAULT_QQ_PATTERN);

    private static final ForgeConfigSpec.BooleanValue DEBUG_LOG = BUILDER
            .comment("是否输出调试日志")
            .define("debugLog", false);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    // ---- 运行时字段（配置加载时同步） ----
    public static boolean enableVerification = true;
    public static String verifyUrl = "";
    public static boolean reverifyOnLaunch = false;
    public static int httpTimeoutMs = 8000;
    public static int openDelayTicks = 20;
    public static boolean blockEscape = true;
    public static boolean crashOnReject = true;
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
        crashOnReject = CRASH_ON_REJECT.get();
        debugLog = DEBUG_LOG.get();

        String pattern = QQ_PATTERN.get();
        try {
            qqRegex = Pattern.compile(pattern);
        } catch (Exception e) {
            Northstarclientverification.LOGGER.warn("[NorthStar] 配置中的 qqPattern 非法：{}，回退到默认规则", pattern);
            qqRegex = Pattern.compile(DEFAULT_QQ_PATTERN);
        }

        Northstarclientverification.LOGGER.info(
                "[NorthStar] 配置已加载：enableVerification={}, verifyUrl={}, httpTimeoutMs={}, crashOnReject={}",
                enableVerification, verifyUrl.isEmpty() ? "(未配置)" : verifyUrl, httpTimeoutMs, crashOnReject);
    }
}
