package com.ming.northstarclientverification.client;

import com.ming.northstarclientverification.Config;
import com.ming.northstarclientverification.Northstarclientverification;
import com.ming.northstarclientverification.verification.RemoteVerifier;
import com.ming.northstarclientverification.verification.VerificationEntry;
import com.ming.northstarclientverification.verification.VerificationRejectedException;
import com.ming.northstarclientverification.verification.VerificationStore;
import com.ming.northstarclientverification.verification.VerifyResult;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端验证流程的触发点。
 *
 * <p>游戏启动后一旦出现主界面（{@link TitleScreen}），等待若干刻就弹出
 * {@link VerificationScreen}。<b>自 v3.8 起每次启动游戏都要重新验证</b>——
 * 本地记录不再是免验证凭据，只用来预填上次填过的 QQ 号
 * （由 {@code Config.rememberQq} 控制）。</p>
 *
 * <p>唯一的例外是 {@code Config.skipPlayers} 名单（默认 {@code JeffreyMing,beigang,TiaraY}）：
 * 名单内的游戏 ID 既不弹窗也不发请求，直接放行。</p>
 *
 * <p>身份只由「QQ + 游戏 ID」确定。本服为离线模式，玩家 UUID 每次启动可能变化，
 * 因此本地记录按游戏 ID 索引，也不向远端上报 UUID。</p>
 *
 * <p>通过 {@code value = Dist.CLIENT} 注册，专用服务端不会加载本类。</p>
 */
@Mod.EventBusSubscriber(modid = Northstarclientverification.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class ClientVerificationHandler {

    /** 倒计时尚未初始化。 */
    private static final int PENDING = -1;

    private static int countdown = PENDING;
    private static boolean storeLoaded;
    /** 本次启动已放行，不再干预界面。 */
    private static boolean finished;
    /** 玩家主动关闭了验证窗口（仅在允许 ESC 时会发生），本次启动不再拦截。 */
    private static boolean dismissed;

    private ClientVerificationHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished || dismissed || !Config.enableVerification) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        // 资源还没加载完 / 还没有任何界面
        if (minecraft.screen == null) {
            return;
        }
        // 只拦截主界面；已经进入世界或打开了其它界面就不干预
        if (!(minecraft.screen instanceof TitleScreen)) {
            countdown = PENDING;
            return;
        }

        if (!storeLoaded) {
            VerificationStore.get().load();
            storeLoaded = true;
        }

        if (countdown == PENDING) {
            countdown = Math.max(0, Config.openDelayTicks);
        }
        if (countdown > 0) {
            countdown--;
            return;
        }

        String playerName = ClientIdentity.name(minecraft);

        // 免验证名单：既不弹窗也不发请求，直接放行
        if (Config.shouldSkipVerification(playerName)) {
            finished = true;
            Northstarclientverification.LOGGER.info("[NorthStar] 游戏 ID {} 在免验证名单中，跳过验证", playerName);
            return;
        }

        // 每次启动都要重新验证；本地记录只用来预填上次用过的 QQ
        VerificationEntry remembered = Config.rememberQq ? VerificationStore.get().find(playerName) : null;
        if (Config.debugLog && remembered != null) {
            Northstarclientverification.LOGGER.info("[NorthStar] 已记住游戏 ID {} 上次使用的 QQ，本次自动填入", playerName);
        }

        minecraft.setScreen(new VerificationScreen(minecraft.screen, playerName,
                remembered == null ? null : remembered.getQq()));
    }

    /**
     * {@link VerificationScreen} 验证通过后调用：本次启动不再拦截。
     *
     * <p>这个方法<b>不能省</b>。旧版本靠「本地已有验证记录」来避免通过后重复弹窗，
     * 而 v3.8 已经不再用本地记录判放行——若没有这行，玩家验证通过回到主界面后
     * 会被立刻再次弹窗，形成永远进不去的死循环。</p>
     */
    public static void onVerificationPassed() {
        finished = true;
    }

    /** {@link VerificationScreen#onClose()} 调用：玩家主动放弃验证。 */
    public static void onPromptDismissed() {
        dismissed = true;
        Northstarclientverification.LOGGER.info("[NorthStar] 玩家关闭了验证窗口，本次启动不再拦截");
    }

    /**
     * 远端明确判定"验证未通过"且<b>机会已用尽</b>：构造崩溃报告并结束游戏。
     *
     * <p>只有累计失败次数达到 {@code Config.maxAttempts}（默认 3）时才会走到这里；
     * 没达上限时 {@link VerificationScreen} 会留在界面提示剩余次数，不调用本方法。</p>
     *
     * <p>崩溃报告顶部会包含 {@link Northstarclientverification#REJECT_CRASH_DESCRIPTION}，
     * 异常信息为 {@link Northstarclientverification#REJECT_MESSAGE}，同时附带游戏 ID、QQ 号、
     * 接口地址、HTTP 状态码与响应内容，方便排查。</p>
     */
    public static void crashOnRejected(String playerName, String qq, VerifyResult result) {
        VerificationRejectedException cause = new VerificationRejectedException(
                Northstarclientverification.REJECT_MESSAGE,
                playerName, qq, result.url(), result.httpCode(), result.body());

        Northstarclientverification.LOGGER.error("[NorthStar] {}（游戏ID={} QQ={} HTTP={} 响应={}）",
                Northstarclientverification.REJECT_MESSAGE, playerName, qq,
                result.httpCode(), RemoteVerifier.abbreviate(result.body(), 500));

        try {
            CrashReport report = CrashReport.forThrowable(cause, Northstarclientverification.REJECT_CRASH_DESCRIPTION);
            CrashReportCategory category = report.addCategory("NorthStar 内测验证");
            category.setDetail("结果", "您的内测验证未通过，尝试次数已用完");
            category.setDetail("失败次数", VerificationStore.get().failureCount(playerName) + " / " + Config.maxAttempts);
            category.setDetail("游戏ID", playerName);
            category.setDetail("提交的 QQ 号", qq);
            category.setDetail("验证接口", result.url());
            category.setDetail("HTTP 状态码", result.httpCode());
            category.setDetail("响应内容", RemoteVerifier.abbreviate(result.body(), 500));

            // delayCrash 会在主线程填充完整报告，然后写入 crash-reports/ 并结束进程
            Minecraft.getInstance().delayCrash(report);
        } catch (Throwable failure) {
            // 兜底：万一崩溃报告模块自身出问题，直接把异常抛到主线程——
            // Minecraft 仍会生成崩溃报告，异常信息同样是 REJECT_MESSAGE，只是少了自定义分类。
            Northstarclientverification.LOGGER.error("[NorthStar] 构造崩溃报告失败，改为直接抛出异常", failure);
            throw cause;
        }
    }
}
