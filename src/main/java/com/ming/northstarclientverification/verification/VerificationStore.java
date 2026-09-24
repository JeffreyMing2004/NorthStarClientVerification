package com.ming.northstarclientverification.verification;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.ming.northstarclientverification.Config;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 验证数据的读写入口（单例）。
 *
 * <p>落盘位置固定在 <code>&lt;游戏目录&gt;/config/northstar/verification.json</code>，
 * 文件结构：</p>
 *
 * <pre>{@code
 * {
 *   "version": 3,
 *   "players": {
 *     "steve": {
 *       "name": "Steve",
 *       "qq": "123456789",
 *       "firstVerifiedAt": 1758680000000,
 *       "lastVerifiedAt": 1758680000000
 *     }
 *   },
 *   "failures": {
 *     "alex": 2
 *   }
 * }
 * }</pre>
 *
 * <p><b>键是游戏 ID（小写）而不是 UUID</b>：本服为离线模式，UUID 每次启动都可能变，
 * 用它做键会导致每次开游戏都重新弹窗。旧版本（version 1）按 UUID 索引的文件会在
 * 载入时自动迁移到按游戏 ID 索引。</p>
 *
 * <p><b>{@code failures} 必须与 {@code players} 分开存</b>：{@link #isVerified(String)}
 * 的判据是「{@code players} 里有条目」，若把失败次数塞进同一条目，则一个从未通过、
 * 只是填错了两次的玩家会被当成已通过而直接放行——这是安全漏洞，不是显示问题。</p>
 */
public final class VerificationStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String DIR_NAME = "northstar";
    private static final String FILE_NAME = "verification.json";

    /** 2 = players 的键由「玩家 UUID」改为「游戏 ID（小写）」；3 = 新增 failures。 */
    private static final int FORMAT_VERSION = 3;

    private static final VerificationStore INSTANCE = new VerificationStore();

    private final Object lock = new Object();

    private Root root = new Root();

    private VerificationStore() {
    }

    public static VerificationStore get() {
        return INSTANCE;
    }

    /** 验证文件的绝对路径。 */
    public static Path resolveFile() {
        return FMLPaths.CONFIGDIR.get().resolve(DIR_NAME).resolve(FILE_NAME);
    }

    public Path getFile() {
        return resolveFile();
    }

    /**
     * 从磁盘重新载入验证数据。文件不存在时视为空表；文件损坏时备份后重建。
     *
     * <p>读到 1 版（按 UUID 索引）的文件会自动改写为 2 版，因此外部手工修改文件
     * 同样生效。</p>
     */
    public void load() {
        synchronized (lock) {
            Path file = resolveFile();

            if (!Files.exists(file)) {
                root = new Root();
                debug("验证文件不存在，将在首次验证时创建：{}", file);
                return;
            }

            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Root parsed = GSON.fromJson(reader, Root.class);
                root = parsed == null ? new Root() : parsed;
                if (root.players == null) {
                    root.players = new LinkedHashMap<>();
                }
                if (root.failures == null) {
                    root.failures = new LinkedHashMap<>();
                }

                boolean migrated = migrateKeys();
                boolean outdated = root.version < FORMAT_VERSION;
                if (root.version <= 0) {
                    root.version = FORMAT_VERSION;
                }

                if (migrated || outdated) {
                    root.version = FORMAT_VERSION;
                    try {
                        save();
                        LOGGER.info("[NorthStar] 验证文件已升级到 v{}：{}", FORMAT_VERSION, file);
                    } catch (IOException e) {
                        // 升级失败不影响本次使用，下次启动会再试一次
                        LOGGER.warn("[NorthStar] 写回升级后的验证文件失败：{}", file, e);
                    }
                }

                LOGGER.info("[NorthStar] 已载入 {} 条验证记录：{}", root.players.size(), file);
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.error("[NorthStar] 读取验证文件失败，将备份并重建：{}", file, e);
                backupBrokenFile(file);
                root = new Root();
            }
        }
    }

    /** 该玩家（游戏 ID）是否已经验证过。 */
    public boolean isVerified(String playerName) {
        return find(playerName) != null;
    }

    /** 查找玩家已有的验证记录，没有则返回 null。游戏 ID 忽略大小写。 */
    public VerificationEntry find(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        synchronized (lock) {
            return root.players.get(keyOf(playerName));
        }
    }

    /**
     * 该玩家累计的「明确未通过」次数，没有记录时返回 0。
     *
     * <p>与 {@link #find(String)} 是<b>两套独立的数据</b>：填错 QQ 不会往 players 里写条目，
     * 所以失败过的玩家不会被 {@link #isVerified(String)} 误判为已通过。</p>
     */
    public int failureCount(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return 0;
        }
        synchronized (lock) {
            return failureCountLocked(keyOf(playerName));
        }
    }

    /**
     * 记一次「明确未通过」并立即落盘。
     *
     * @param playerName 游戏 ID
     * @return <b>包含本次</b>在内的累计失败次数（即本次是第几次）
     */
    public int recordFailure(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return 0;
        }

        synchronized (lock) {
            String key = keyOf(playerName);
            int next = failureCountLocked(key) + 1;
            root.failures.put(key, next);

            try {
                save();
                LOGGER.warn("[NorthStar] 玩家 {} 第 {} 次验证未通过，已记入 {}",
                        playerName, next, resolveFile());
            } catch (IOException e) {
                // 落盘失败也照常返回计数：宁可本次少一次提示，也不要因为写不进去就放行
                LOGGER.error("[NorthStar] 写入失败次数失败（本次仍按第 {} 次计）：{}",
                        next, resolveFile(), e);
            }
            return next;
        }
    }

    /**
     * 清零某个玩家（游戏 ID）的失败计数。验证通过时调用。
     *
     * @return 是否写盘成功；失败时内存计数仍已清零，仅提示调用方
     */
    public boolean clearFailures(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return true;
        }

        synchronized (lock) {
            String key = keyOf(playerName);
            if (failureCountLocked(key) == 0) {
                return true;
            }
            root.failures.remove(key);
            try {
                save();
                return true;
            } catch (IOException e) {
                LOGGER.error("[NorthStar] 清除失败次数失败：{}", resolveFile(), e);
                return false;
            }
        }
    }

    private int failureCountLocked(String key) {
        Integer count = root.failures.get(key);
        return count == null ? 0 : Math.max(0, count);
    }

    /**
     * 记录一次验证并立即落盘，同时<b>清零该玩家的失败计数</b>。
     *
     * @param playerName 游戏 ID，必须非空
     * @return 写入成功返回 true；写入失败时内存状态会回滚并返回 false
     */
    public boolean record(String playerName, String qq) {
        if (playerName == null || playerName.isEmpty() || qq == null || qq.isEmpty()) {
            return false;
        }

        synchronized (lock) {
            String key = keyOf(playerName);
            long now = System.currentTimeMillis();

            // 保存旧记录快照，写入失败时用于回滚
            VerificationEntry existing = root.players.get(key);
            String snapshot = existing == null ? null : GSON.toJson(existing);
            Integer failureSnapshot = root.failures.get(key);

            if (existing != null) {
                existing.update(playerName, qq, now);
            } else {
                root.players.put(key, new VerificationEntry(playerName, qq, now));
            }
            // 验证通过即清零失败计数：机会是「连续」的，成功一次就重置
            root.failures.remove(key);

            try {
                save();
                LOGGER.info("[NorthStar] 玩家 {} 验证完成，QQ={}，已写入 {}", playerName, qq, resolveFile());
                return true;
            } catch (IOException e) {
                LOGGER.error("[NorthStar] 写入验证文件失败：{}", resolveFile(), e);
                // 回滚内存状态，避免出现“本次会话已通过、重启后又弹窗”的错觉
                if (snapshot == null) {
                    root.players.remove(key);
                } else {
                    root.players.put(key, GSON.fromJson(snapshot, VerificationEntry.class));
                }
                if (failureSnapshot == null) {
                    root.failures.remove(key);
                } else {
                    root.failures.put(key, failureSnapshot);
                }
                return false;
            }
        }
    }

    /**
     * 把 1 版文件里按 UUID 索引、或大小写不一致的条目改挂到「游戏 ID（小写）」键下。
     *
     * @return 是否发生了改动（需要写回磁盘）
     */
    private boolean migrateKeys() {
        Map<String, VerificationEntry> migrated = new LinkedHashMap<>();
        boolean changed = false;

        for (Map.Entry<String, VerificationEntry> item : root.players.entrySet()) {
            VerificationEntry entry = item.getValue();
            if (entry == null) {
                // 丢弃坏条目
                changed = true;
                continue;
            }

            String name = entry.getName();
            String key = (name == null || name.isEmpty()) ? item.getKey() : keyOf(name);

            if (!key.equals(item.getKey())) {
                changed = true;
                LOGGER.info("[NorthStar] 验证记录索引迁移：{} -> {}（游戏 ID：{}）", item.getKey(), key, name);
            }
            migrated.putIfAbsent(key, entry);
        }

        root.players = migrated;
        return changed;
    }

    /** players 的键：游戏 ID 统一转小写，避免改名大小写后重复弹窗。 */
    private static String keyOf(String playerName) {
        return playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
    }

    /** 原子写入：先写 .tmp 再替换，尽量避免游戏崩溃时留下半个文件。 */
    private void save() throws IOException {
        Path file = resolveFile();
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
            writer.flush();
        }

        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void backupBrokenFile(Path file) {
        try {
            Path backup = file.resolveSibling(FILE_NAME + ".bak");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("[NorthStar] 已备份损坏的验证文件到 {}", backup);
        } catch (IOException e) {
            LOGGER.warn("[NorthStar] 备份损坏的验证文件失败", e);
        }
    }

    private static void debug(String message, Object... args) {
        if (Config.debugLog) {
            LOGGER.info("[NorthStar] " + message, args);
        }
    }

    /** verification.json 的根对象。 */
    public static final class Root {
        private int version = FORMAT_VERSION;
        private Map<String, VerificationEntry> players = new LinkedHashMap<>();
        /**
         * 游戏 ID（小写）→ 累计「明确未通过」次数。
         *
         * <p>必须独立于 {@link #players}：players 里的条目意味着「已验证通过」。</p>
         */
        private Map<String, Integer> failures = new LinkedHashMap<>();
    }
}
