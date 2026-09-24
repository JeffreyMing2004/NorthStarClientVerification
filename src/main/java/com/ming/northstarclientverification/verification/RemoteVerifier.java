package com.ming.northstarclientverification.verification;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.ming.northstarclientverification.Config;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 远端验证客户端。
 *
 * <p>向配置项 {@code verifyUrl} 发起 <b>GET</b> 请求，自动附加两个查询参数：</p>
 *
 * <pre>{@code
 * GET {verifyUrl}?qq=123456789&name=Steve
 * }</pre>
 *
 * <p><b>为什么只有 qq 和 name 两个参数</b>：本服为离线模式，玩家 UUID 由启动器各自
 * 生成、每次启动可能不同，后端无法据此判断身份。所以身份只由「QQ + 游戏 ID」确定，
 * 客户端不再上报 UUID。</p>
 *
 * <p>判定规则：</p>
 * <ul>
 *   <li>HTTP 2xx 且响应体 JSON 中 {@code success} 为 {@code true}（或 {@code code} 为 {@code 0} / {@code 200}）→ 通过</li>
 *   <li>HTTP 2xx 且 {@code success} 为 {@code false}（或 {@code code} 既不是 {@code 0} 也不是 {@code 200}）→ 明确未通过</li>
 *   <li>网络异常 / 超时 / 非 2xx / 响应体解析不出上述字段 → 服务不可用，可重试</li>
 * </ul>
 *
 * <p><b>为什么 {@code code} 既认 0 也认 200</b>：后端（Spring Boot）沿用
 * {@code {code,message,data}} 风格时普遍用 {@code code:200} 表示成功。
 * 若只认 {@code 0}，正常通过也会被判成「未通过」而崩溃，因此这里两种约定都接受。</p>
 *
 * <p>请求全程异步，不会阻塞游戏主线程。</p>
 */
public final class RemoteVerifier {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 响应体最多保留的字符数，避免把整页 HTML 塞进崩溃报告。 */
    private static final int MAX_BODY_LENGTH = 2000;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private RemoteVerifier() {
    }

    /**
     * 异步发起一次验证请求。
     *
     * @param playerName 游戏 ID（离线模式即登录用户名）
     * @param qq         玩家填写的 QQ 号
     * @return 永远不会以异常结束的 future；异常情况会转成 {@link VerifyResult.Status#ERROR}
     */
    public static CompletableFuture<VerifyResult> verify(String playerName, String qq) {
        String base = Config.verifyUrl == null ? "" : Config.verifyUrl.trim();

        if (base.isEmpty()) {
            LOGGER.warn("[NorthStar] 未配置 verifyUrl，跳过远端校验，仅写入本地 verification.json");
            return CompletableFuture.completedFuture(VerifyResult.skipped(""));
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return CompletableFuture.completedFuture(
                    VerifyResult.error(base, -1, "verifyUrl 必须以 http:// 或 https:// 开头"));
        }

        final String url = buildUrl(base, playerName, qq);

        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder(new URI(url))
                    .timeout(Duration.ofMillis(Math.max(1000, Config.httpTimeoutMs)))
                    .header("Accept", "application/json")
                    .header("User-Agent", "NorthStarClientVerification/1.0")
                    .GET()
                    .build();
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    VerifyResult.error(url, -1, "请求构造失败：" + describe(e)));
        }

        if (Config.debugLog) {
            LOGGER.info("[NorthStar] GET {}", url);
        }

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        String reason = describe(throwable);
                        LOGGER.error("[NorthStar] 验证请求失败：{} -> {}", url, reason);
                        return VerifyResult.error(url, -1, reason);
                    }
                    VerifyResult result = interpret(url, response.statusCode(), response.body());
                    if (Config.debugLog) {
                        LOGGER.info("[NorthStar] {} -> HTTP {} / {}", url, response.statusCode(), result.status());
                    }
                    return result;
                });
    }

    /** 拼查询串；若配置的地址本身已带 ?，则用 & 追加。 */
    private static String buildUrl(String base, String playerName, String qq) {
        StringBuilder sb = new StringBuilder(base.length() + 64);
        sb.append(base);
        sb.append(base.indexOf('?') >= 0 ? '&' : '?');
        sb.append("qq=").append(encode(qq));
        sb.append("&name=").append(encode(playerName));
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static VerifyResult interpret(String url, int httpCode, String rawBody) {
        String body = abbreviate(rawBody, MAX_BODY_LENGTH);

        if (httpCode < 200 || httpCode >= 300) {
            return VerifyResult.error(url, httpCode, "HTTP " + httpCode);
        }

        Boolean success = readSuccessFlag(rawBody);
        if (success == null) {
            return VerifyResult.error(url, httpCode, "响应体解析不出 success / code 字段");
        }
        return success
                ? VerifyResult.success(httpCode, body, url)
                : VerifyResult.rejected(httpCode, body, url);
    }

    /**
     * 从响应体里读出"是否通过"。
     *
     * @return true / false；无法判定时返回 null（按服务不可用处理）
     */
    private static Boolean readSuccessFlag(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(body.trim());
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject object = root.getAsJsonObject();

            if (object.has("success") && object.get("success").isJsonPrimitive()) {
                JsonPrimitive value = object.getAsJsonPrimitive("success");
                if (value.isBoolean()) {
                    return value.getAsBoolean();
                }
                if (value.isString()) {
                    return "true".equalsIgnoreCase(value.getAsString());
                }
                return null;
            }

            if (object.has("code") && object.get("code").isJsonPrimitive()) {
                JsonPrimitive value = object.getAsJsonPrimitive("code");
                if (value.isNumber() || value.isString()) {
                    try {
                        return isSuccessCode(value.getAsInt());
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code 0}（自定义约定）与 {@code 200}（HTTP 风格 / 后端 ApiResponse）都算通过。 */
    private static boolean isSuccessCode(int code) {
        return code == 0 || code == 200;
    }

    /** 单行化并截断文本，便于放进日志与崩溃报告。 */
    public static String abbreviate(String text, int max) {
        if (text == null || text.isEmpty()) {
            return "(空)";
        }
        String flat = text.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + " ...(已截断)";
    }

    private static String describe(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
