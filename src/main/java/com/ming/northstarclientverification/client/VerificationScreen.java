package com.ming.northstarclientverification.client;

import com.ming.northstarclientverification.Config;
import com.ming.northstarclientverification.Northstarclientverification;
import com.ming.northstarclientverification.verification.AttemptPolicy;
import com.ming.northstarclientverification.verification.QqValidator;
import com.ming.northstarclientverification.verification.RemoteVerifier;
import com.ming.northstarclientverification.verification.VerificationStore;
import com.ming.northstarclientverification.verification.VerifyResult;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * QQ 号内测验证窗口。
 *
 * <p>游戏启动进入主界面时由 {@link ClientVerificationHandler} 弹出，<b>每次启动都要走一遍</b>；
 * 在 {@code Config.skipPlayers} 名单里的游戏 ID 则根本不会看到本窗口。玩家输入 QQ 号后，
 * 会异步向远端接口发起 GET 校验，同时上报自动识别到的游戏 ID：</p>
 * <ul>
 *   <li>通过 → 记下 QQ 号到 {@code config/northstar/verification.json}（供下次启动预填）
 *       并回到主界面</li>
 *   <li>明确未通过 → 累计一次失败；<b>未达 {@code maxAttempts}（默认 3）次时留在界面提示剩余机会</b>，
 *       达到上限才交由 {@link ClientVerificationHandler#crashOnRejected} 生成崩溃报告并退出游戏</li>
 *   <li>服务不可用 → 停在当前界面提示原因，允许无限重试（不消耗机会）</li>
 * </ul>
 *
 * <p><b>失败次数是「本次启动」内的计数</b>，只存在内存里，重启游戏即归零——否则重启后
 * 第一次填错就会被上一次留下的计数直接拖到崩溃。判定规则见
 * {@link com.ming.northstarclientverification.verification.AttemptPolicy}。</p>
 *
 * <p>窗口上会显示自动识别到的游戏 ID，方便玩家在验证被拒时把它报给管理员核对绑定。</p>
 */
@OnlyIn(Dist.CLIENT)
public class VerificationScreen extends Screen {

    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 186;
    private static final int ACCENT_WIDTH = 3;
    private static final int BUTTON_WIDTH = 120;

    private static final int COLOR_PANEL = 0xF0141618;
    private static final int COLOR_BORDER = 0xFF3A4149;
    private static final int COLOR_ACCENT = 0xFF4C9AFF;
    private static final int COLOR_TITLE = 0xFFF2F5F8;
    private static final int COLOR_TEXT = 0xFFB4BDC7;
    private static final int COLOR_INFO = 0xFF7FB4FF;
    private static final int COLOR_ERROR = 0xFFFF7A7A;

    /** 被拦截掉的原界面，验证通过后回到它。 */
    private final Screen parent;
    /** 自动识别到的游戏 ID，验证时随请求上报。 */
    private final String playerName;
    /** 上次通过验证时用过的 QQ，用于预填；首次游玩或关闭记住功能时为 null。 */
    private final String existingQq;

    private EditBox qqBox;
    private Button confirmButton;
    private Button quitButton;
    private Component statusMessage;
    private int statusColor = COLOR_TEXT;
    private boolean verifying;
    /** 验证通过并已切换界面；为 true 时 onClose 不再视为"玩家主动放弃"。 */
    private boolean completed;

    public VerificationScreen(Screen parent, String playerName, String existingQq) {
        super(Component.translatable("northstar.verification.title"));
        this.parent = parent;
        this.playerName = playerName;
        this.existingQq = existingQq;
    }

    @Override
    protected void init() {
        super.init();

        int panelX = panelLeft();
        int panelY = panelTop();

        this.qqBox = new EditBox(this.font, panelX + 40, panelY + 76, PANEL_WIDTH - 80, 20,
                Component.translatable("northstar.verification.hint"));
        this.qqBox.setMaxLength(11);
        this.qqBox.setHint(Component.translatable("northstar.verification.hint"));
        // 只允许输入数字，避免玩家把中文/空格一起提交
        this.qqBox.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        if (this.existingQq != null && !this.existingQq.isEmpty()) {
            this.qqBox.setValue(this.existingQq);
        }
        this.addRenderableWidget(this.qqBox);
        this.setInitialFocus(this.qqBox);

        int buttonY = panelY + 136;

        this.confirmButton = Button.builder(Component.translatable("northstar.verification.button.confirm"), b -> this.submit())
                .bounds(panelX + 40, buttonY, BUTTON_WIDTH, 20)
                .build();
        this.addRenderableWidget(this.confirmButton);

        // 退出按钮始终可用，避免请求卡住时玩家无法离开
        this.quitButton = Button.builder(Component.translatable("northstar.verification.button.quit"),
                        b -> this.minecraft.stop())
                .bounds(panelX + PANEL_WIDTH - 40 - BUTTON_WIDTH, buttonY, BUTTON_WIDTH, 20)
                .build();
        this.addRenderableWidget(this.quitButton);

        this.applyVerifyingState();

        // init 在窗口缩放时会被再次调用，已有状态提示时不覆盖
        if (this.statusMessage == null) {
            int used = VerificationStore.get().failureCount(this.playerName);
            if (used > 0) {
                // 本次启动内已经填错过：先把剩余机会讲清楚，别等玩家提交完才知道
                int remaining = AttemptPolicy.remaining(used, Config.maxAttempts);
                setStatus(remaining > 0
                                ? Component.translatable("northstar.verification.attempts.remaining",
                                        remaining, Config.maxAttempts)
                                : Component.translatable("northstar.verification.attempts.exhausted",
                                        Config.maxAttempts),
                        remaining > 0 ? COLOR_INFO : COLOR_ERROR);
            } else if (this.existingQq != null && !this.existingQq.isEmpty()) {
                setStatus(Component.translatable("northstar.verification.remembered"), COLOR_INFO);
            } else if (Config.maxAttempts > 1) {
                setStatus(Component.translatable("northstar.verification.attempts.total", Config.maxAttempts),
                        COLOR_INFO);
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 1.20.1 的 renderBackground 只接受 GuiGraphics；主界面下会画成菜单同款泥土背景
        this.renderBackground(guiGraphics);

        int panelX = panelLeft();
        int panelY = panelTop();

        guiGraphics.fill(panelX - 1, panelY - 1, panelX + PANEL_WIDTH + 1, panelY + PANEL_HEIGHT + 1, COLOR_BORDER);
        guiGraphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, COLOR_PANEL);
        guiGraphics.fill(panelX, panelY, panelX + ACCENT_WIDTH, panelY + PANEL_HEIGHT, COLOR_ACCENT);

        int centerX = this.width / 2;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, panelY + 16, COLOR_TITLE);
        guiGraphics.drawCenteredString(this.font, Component.translatable("northstar.verification.subtitle"),
                centerX, panelY + 34, COLOR_TEXT);
        // 游戏 ID 会一起上报校验，显示出来方便玩家在验证被拒时报给管理员
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("northstar.verification.player", this.playerName),
                centerX, panelY + 52, COLOR_INFO);

        if (this.statusMessage != null) {
            guiGraphics.drawCenteredString(this.font, this.statusMessage, centerX, panelY + 106, this.statusColor);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && !this.verifying) {
            this.submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !Config.blockEscape;
    }

    @Override
    public void onClose() {
        if (!this.completed) {
            // 玩家主动关掉（只可能发生在 blockEscape=false 时），本次启动不再拦截
            ClientVerificationHandler.onPromptDismissed();
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent != null ? this.parent : new TitleScreen());
        }
    }

    // ------------------------------------------------------------------ 提交

    private void submit() {
        if (this.verifying) {
            return;
        }

        String qq = QqValidator.normalize(this.qqBox.getValue());
        if (qq.isEmpty()) {
            setStatus(Component.translatable("northstar.verification.error.empty"), COLOR_ERROR);
            return;
        }
        if (!QqValidator.isValid(qq)) {
            setStatus(Component.translatable("northstar.verification.error.format"), COLOR_ERROR);
            return;
        }

        this.verifying = true;
        setStatus(Component.translatable("northstar.verification.status.verifying"), COLOR_INFO);
        applyVerifyingState();

        RemoteVerifier.verify(this.playerName, qq)
                .whenComplete((result, throwable) -> Minecraft.getInstance()
                        .execute(() -> this.handleResult(qq, result, throwable)));
    }

    private void handleResult(String qq, VerifyResult result, Throwable throwable) {
        // 界面已被关闭（例如玩家点了"退出游戏"），丢弃这次结果
        if (Minecraft.getInstance().screen != this) {
            return;
        }

        if (result == null) {
            this.verifying = false;
            applyVerifyingState();
            setStatus(Component.translatable("northstar.verification.error.network",
                    throwable == null ? "unknown" : String.valueOf(throwable.getMessage())), COLOR_ERROR);
            return;
        }

        switch (result.status()) {
            case SUCCESS, SKIPPED -> this.onPassed(qq, result);
            case REJECTED -> this.onRejected(qq, result);
            case ERROR -> {
                this.verifying = false;
                applyVerifyingState();
                setStatus(describeError(result), COLOR_ERROR);
            }
        }
    }

    /** 校验通过：记下 QQ（供下次启动预填）并回到主界面。 */
    private void onPassed(String qq, VerifyResult result) {
        if (!VerificationStore.get().record(this.playerName, qq)) {
            this.verifying = false;
            applyVerifyingState();
            setStatus(Component.translatable("northstar.verification.error.save"), COLOR_ERROR);
            return;
        }

        Northstarclientverification.LOGGER.info("[NorthStar] 游戏 ID {} 内测验证通过，QQ={}（来源：{}）",
                this.playerName, qq,
                result.status() == VerifyResult.Status.SKIPPED ? "未配置接口，本地放行" : "远端校验");

        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.translatable("northstar.verification.success", qq).withStyle(ChatFormatting.GREEN), false);
            this.minecraft.player.displayClientMessage(
                    Component.translatable("northstar.verification.saved", VerificationStore.resolveFile().toString())
                            .withStyle(ChatFormatting.GRAY), false);
        }

        this.completed = true;
        // 必须显式告知 handler：v3.8 起不再靠本地记录判断「已通过」，
        // 少了这行会在回到主界面后被立刻重新弹窗
        ClientVerificationHandler.onVerificationPassed();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent != null ? this.parent : new TitleScreen());
        }
    }

    /** 远端明确判定未通过：累计一次失败，未用尽则留在界面重试。 */
    private void onRejected(String qq, VerifyResult result) {
        int used = VerificationStore.get().recordFailure(this.playerName);
        int limit = Config.maxAttempts;
        int remaining = AttemptPolicy.remaining(used, limit);

        Northstarclientverification.LOGGER.warn(
                "[NorthStar] 游戏 ID {} 内测验证未通过（第 {}/{} 次，剩余 {} 次）：QQ={} HTTP={} 响应={}",
                this.playerName, used, limit, remaining, qq,
                result.httpCode(), RemoteVerifier.abbreviate(result.body(), 200));

        if (AttemptPolicy.decide(used, limit, Config.crashWhenExhausted) == AttemptPolicy.Decision.CRASH) {
            setStatus(Component.translatable("northstar.verification.error.rejected.exhausted", limit), COLOR_ERROR);
            ClientVerificationHandler.crashOnRejected(this.playerName, qq, result);
            return;
        }

        this.verifying = false;
        applyVerifyingState();
        setStatus(Config.crashWhenExhausted
                        ? Component.translatable("northstar.verification.error.rejected.remaining", remaining, limit)
                        : Component.translatable("northstar.verification.error.rejected"),
                COLOR_ERROR);
    }

    // ------------------------------------------------------------------ 界面状态

    private void setStatus(Component message, int color) {
        this.statusMessage = message;
        this.statusColor = color;
    }

    private void applyVerifyingState() {
        boolean idle = !this.verifying;
        if (this.qqBox != null) {
            this.qqBox.setEditable(idle);
        }
        if (this.confirmButton != null) {
            this.confirmButton.active = idle;
            this.confirmButton.setMessage(Component.translatable(idle
                    ? "northstar.verification.button.confirm"
                    : "northstar.verification.button.verifying"));
        }
    }

    private Component describeError(VerifyResult result) {
        String reason = result.error() == null || result.error().isBlank() ? "(未知原因)" : result.error();
        if (result.httpCode() > 0) {
            return Component.translatable("northstar.verification.error.http", result.httpCode(), reason);
        }
        return Component.translatable("northstar.verification.error.network", reason);
    }

    private int panelLeft() {
        return (this.width - PANEL_WIDTH) / 2;
    }

    private int panelTop() {
        return (this.height - PANEL_HEIGHT) / 2;
    }
}
