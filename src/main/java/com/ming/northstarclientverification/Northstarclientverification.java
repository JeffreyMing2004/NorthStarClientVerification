package com.ming.northstarclientverification;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * NorthStar 客户端验证 Mod 主类。
 *
 * <p>玩法：游戏启动进入主界面时弹出 QQ 内测验证窗口，向远端接口发起 GET 校验；
 * 通过后把结果写入 {@code config/northstar/verification.json}，之后启动不再弹窗。</p>
 *
 * <p>本 Mod 只在客户端生效，所有客户端代码都通过 {@code Dist.CLIENT}
 * 事件订阅器加载，专用服务端不会触碰相关类。</p>
 */
@Mod(Northstarclientverification.MODID)
public class Northstarclientverification {

    /** 与 META-INF/mods.toml 中的 modId 保持一致。 */
    public static final String MODID = "northstarclientverification";

    /**
     * 验证未通过且<b>机会已用尽</b>时写进崩溃报告的 Description（会出现在 crash-reports 文件顶部）。
     *
     * <p>注意措辞：走到这里游戏马上就要退出，所以写「尝试次数已用完」而不是「请重试」。</p>
     */
    public static final String REJECT_CRASH_DESCRIPTION = "northstar 北极战区：您的内测验证未通过，尝试次数已用完";

    /** 抛出异常时携带的说明文案。 */
    public static final String REJECT_MESSAGE = "因northstar 北极战区：您的内测验证未通过，尝试次数已用完";

    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Forge 47.x 起推荐通过构造器注入 {@link FMLJavaModLoadingContext}，
     * 而不是调用已标记移除的 {@code ModLoadingContext.get()}。
     */
    public Northstarclientverification(FMLJavaModLoadingContext context) {
        // 注册通用配置（config/northstarclientverification-common.toml）
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        LOGGER.info("[NorthStar] 客户端验证 Mod 已加载");
    }
}
