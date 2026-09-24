# NorthStar Client Verification

NorthStar 北极战区离线服的**内测准入客户端 Mod**：启动游戏进入主界面时弹出验证窗口，
玩家填写 QQ 号，Mod 向平台接口校验「QQ + 游戏ID」是否拥有内测资格，通过后正常进入游戏。

- Minecraft **1.20.1** / Forge **47.4.23**（47.x 均可）
- **仅客户端**：服务端不需要安装，也不会因服务端缺失而出现红叉
- 验证平台：<https://northstar.mingpixel.net>
- 相关仓库：[`northstar_Backend`](https://github.com/JeffreyMing2004/northstar_Backend)（Spring Boot 后端）、
  [`northstar_frontend`](https://github.com/JeffreyMing2004/northstar_frontend)（平台前端）

## 行为

| 场景 | 表现 |
| --- | --- |
| 启动游戏 | 主界面出现后延迟 `openDelayTicks`（默认 20 刻 = 1 秒）弹出验证窗口 |
| 上次已经通过 | **依然会弹窗** —— 验证每次启动都要重做 |
| 命中免验证名单 | 不弹窗、不发任何请求，直接进入 |
| 提交 QQ | 异步 GET 远端接口，携带 `qq` 与 `name`（自动识别到的游戏 ID） |
| 通过 | 记住本次填写的 QQ（下次预填），回到主界面正常游戏 |
| **明确未通过** | 提示还剩几次机会；默认允许 **3** 次，用完才生成崩溃报告并退出游戏 |
| 服务不可用 | 停在窗口红字提示原因，可无限重试，**不消耗机会** |

### 为什么不用 UUID

本服是离线模式，玩家 UUID 由各启动器本地派生、**每次启动都可能不同**，无法作为身份依据。
所以全链路只认「QQ + 游戏 ID」，请求里也不带 UUID。

### 它是客户端校验，不是安全边界

Mod 装在玩家自己的电脑上，任何玩家都能绕过（改配置文件、换 jar、抓包）。
它用来拦住普通玩家，**不能当作服务器权限控制**。

## 安装

1. 安装 Minecraft 1.20.1 与 Forge 47.x
2. 把 `northstarclientverification-<版本>.jar` 放进 `.minecraft/mods/`
3. 启动游戏，主界面出现后按提示填写 QQ 号

## 配置

首次启动后生成 `.minecraft/config/northstarclientverification-common.toml`。

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `enableVerification` | `true` | 是否弹出验证窗口 |
| `verifyUrl` | 生产接口地址 | 验证接口完整地址（GET）。**留空表示跳过远端校验、直接放行** |
| `rememberQq` | `true` | 记住上次通过验证的 QQ 号，下次弹窗自动填入；关闭则每次都要手输 |
| `skipPlayers` | `JeffreyMing,beigang,TiaraY` | 免验证游戏 ID 名单，英文逗号分隔，忽略大小写与首尾空白 |
| `httpTimeoutMs` | `8000` | 请求超时（毫秒，1000–60000） |
| `openDelayTicks` | `20` | 主界面出现后延迟多少刻弹窗（20 刻 = 1 秒） |
| `blockEscape` | `true` | 验证完成前是否禁用 ESC（仍可用窗口里的「退出游戏」按钮离开） |
| `maxAttempts` | `3` | 允许几次「明确未通过」；用尽才崩溃。**机会每次启动重新给**（只存内存，退出即归零） |
| `crashWhenExhausted` | `true` | 机会用尽后是否崩溃退出；设为 `false` 则永不崩溃，只停在界面提示（调试用） |
| `qqPattern` | `^[1-9]\d{4,10}$` | QQ 号校验正则，需与服务端保持一致 |
| `debugLog` | `false` | 是否输出调试日志 |

### 联调时切到本机后端

把 `verifyUrl` 改成 `http://127.0.0.1:8080/api/beta/verify`，并先在本机启动 `northstar_Backend`。

> ⚠️ `127.0.0.1` 指**玩家自己的电脑**。这个地址只对本机联调有效，
> 随 jar 分发给玩家时必须是生产地址，否则每个玩家都会连不上、卡在验证窗口。

## 本地记录

`config/northstar/verification.json`（格式 v4）：

```json
{
  "version": 4,
  "players": {
    "steve": {
      "name": "Steve",
      "qq": "123456789",
      "firstVerifiedAt": 1758680000000,
      "lastVerifiedAt": 1758680000000
    }
  }
}
```

- `players` 的键是**游戏 ID（小写）**，不是 UUID。按 UUID 索引的旧文件（v1）在载入时自动迁移。
- 这份文件**只用于预填 QQ 号**，与放行无关 —— 「验证过一次以后就免验证」的行为自 v3.8 起已取消。
- 想重新触发一次全新的验证流程：删掉该文件后重启游戏即可。
- 文件损坏时自动备份为 `verification.json.bak` 并重建。

## 构建

需要 **JDK 17**（更高的 JDK 会让 ForgeGradle 构建失败）。

```bash
./gradlew build        # 产物：build/libs/northstarclientverification-<版本>.jar
./gradlew runClient    # 起一个开发用客户端，便于直接看验证窗口
```

## 接口契约

客户端与后端之间的完整约定（请求参数、判定优先级、各响应分支的客户端行为、验收清单）
见 [`docs/NorthStar-内测验证接口文档.md`](docs/NorthStar-内测验证接口文档.md)，
同内容另有 [HTML 版](docs/NorthStar-内测验证接口文档.html)。

最容易踩的三条：

1. 服务端异常必须返回**非 2xx**。`200 + success:false` 会被判为「明确未通过」，消耗玩家机会。
2. `success` 必须是 **JSON 布尔**，不要用 `1` 或 `"1"`（字符串 `"1"` 会被判为未通过）。
3. 判定优先级是 `success` 高于 `code`。

## 许可证

[GNU Lesser General Public License v2.1](LICENSE)，SPDX 标识 `LGPL-2.1-only`。

你可以自由使用、修改本 Mod，并随整合包分发（含商业整合包），无需公开整合包自身代码；
但**对本 Mod 本身的修改必须以 LGPL-2.1 同样开放**。本软件不提供任何担保。

> 以上仅为便于阅读的概述，具体权利义务以 [LICENSE](LICENSE) 全文为准。
