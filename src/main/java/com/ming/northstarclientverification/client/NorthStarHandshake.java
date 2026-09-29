package com.ming.northstarclientverification.client;

import com.ming.northstarclientverification.Northstarclientverification;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;

/**
 * 进服后向服务端发送一条「裸」原版自定义插件消息，证明本 Mod 存在。
 *
 * <p>通道 {@code northstar:verify} <b>不注册为 Forge SimpleChannel</b>，而是直接发
 * {@link ServerboundCustomPayloadPacket}。这样 Mohist / Forge+Bukkit 混合端可以把
 * 未注册的插件消息通道转发给 Bukkit Messenger，服务端插件即可在
 * {@code registerIncomingPluginChannel} 中收到握手；纯 Spigot 服务端同样兼容。</p>
 *
 * <p>触发时机：客户端玩家登录（{@code LoggingIn}）与重生/换维度（{@code Clone}）时各发一次。
 * 发送方只是客户端，服务端不安装本 Mod 也不会红叉。</p>
 */
@Mod.EventBusSubscriber(modid = Northstarclientverification.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class NorthStarHandshake {

    /** 通道命名空间。 */
    public static final String CHANNEL_NAMESPACE = "northstar";

    /** 通道路径。 */
    public static final String CHANNEL_PATH = "verify";

    /** 服务端插件监听的完整通道名，格式为 {@code namespace:path}。 */
    public static final String CHANNEL = CHANNEL_NAMESPACE + ":" + CHANNEL_PATH;

    /** 握手协议版本，必须与服务端插件 {@code config.yml} 中的 protocol-version 一致。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 握手签名，必须与服务端插件 {@code config.yml} 中的 handshake-magic 一致。 */
    public static final String HANDSHAKE_MAGIC = "NorthStar-Client-Verification-v1";

    private NorthStarHandshake() {
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        send(event.getConnection());
    }

    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        send(event.getConnection());
    }

    private static void send(Connection connection) {
        // 内存连接（单机整合服）没有外部服务端，没必要发握手
        if (connection == null || !connection.isConnected() || connection.isMemoryConnection()) {
            return;
        }

        try {
            byte[] modId = Northstarclientverification.MODID.getBytes(StandardCharsets.UTF_8);
            byte[] magic = HANDSHAKE_MAGIC.getBytes(StandardCharsets.UTF_8);

            // 与 NorthStarModTestPlugin 约定的纯字节格式，避免 VarInt / modified-UTF 差异：
            // [0] 协议版本(1字节) [1] modId长度(1字节) [modId字节...] [magic长度(1字节) [magic字节...]
            FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
            payload.writeByte(PROTOCOL_VERSION);
            payload.writeByte(modId.length);
            payload.writeBytes(modId);
            payload.writeByte(magic.length);
            payload.writeBytes(magic);

            connection.send(new ServerboundCustomPayloadPacket(
                    ResourceLocation.fromNamespaceAndPath(CHANNEL_NAMESPACE, CHANNEL_PATH), payload));

            Northstarclientverification.LOGGER.debug("[NorthStar] 已向服务器发送客户端验证握手");
        } catch (Throwable t) {
            Northstarclientverification.LOGGER.warn("[NorthStar] 发送客户端验证握手失败", t);
        }
    }
}

