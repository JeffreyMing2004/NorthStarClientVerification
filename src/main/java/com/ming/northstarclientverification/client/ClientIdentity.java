package com.ming.northstarclientverification.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 读取"当前是谁在验证"。
 *
 * <p>验证窗口在主界面弹出，此时 {@code Minecraft#player} 还是 null，
 * 只能从启动器账号信息 {@link User} 取名字；若玩家已经在世界里
 * （例如管理员手动打开验证界面），则优先用玩家实体。</p>
 *
 * <p><b>为什么不取 UUID</b>：本服是离线（离线验证）模式，UUID 由启动器各自
 * 生成、每次启动都可能不同，也未必与服务端按名字派生的离线 UUID 一致。
 * 拿它做白名单匹配必然误判，因此身份只由「QQ + 游戏ID」两个要素确定。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientIdentity {

    private ClientIdentity() {
    }

    /**
     * 玩家游戏 ID（登录用户名）。离线模式与正版模式都可用，
     * 且与服务端看到的玩家名一致。
     */
    public static String name(Minecraft minecraft) {
        if (minecraft.player != null) {
            return minecraft.player.getGameProfile().getName();
        }
        User user = minecraft.getUser();
        return user == null ? "unknown" : user.getName();
    }
}
