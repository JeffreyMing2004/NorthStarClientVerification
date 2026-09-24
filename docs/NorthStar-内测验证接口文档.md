# NorthStar 内测验证接口文档

| 项目 | 内容 |
| --- | --- |
| 文档版本 | **v3.1**（身份标识为「QQ + 游戏ID」；QQ 由注册平台收集，审批通过后自动同步白名单） |
| 编写日期 | 2026-09-24 |
| 客户端 | NorthStar Client Verification 1.0-SNAPSHOT（Minecraft Forge 1.20.1） |
| 服务端 | northstar_backend（Spring Boot 4.1.1 / Java 17） |
| 接口用途 | 校验玩家的「QQ + 游戏ID」是否具备「北极战区」内测资格 |
| 服务器模式 | **离线（离线验证）模式** —— 玩家没有 Mojang 正版 UUID |

> **v3.0 重大变更**：**UUID 被彻底移出验证流程**。
> 北极战区是离线模式服务器，玩家 UUID 由各启动器自行生成、**每次启动都可能不同**，
> 服务端也无法据其识别玩家，用它做白名单匹配必然误判。
> 自本版起，身份只由 **QQ + 游戏ID** 两项决定：
> 请求参数由 `qq`/`name`/`uuid` 三个减为 **`qq`/`name` 两个**，
> 白名单唯一键由 `qq` 改为 **`(qq, mcId)` 组合**。
>
> **v3.1 补充**：服务端**不启用 MC 白名单**，判定完全在后端做，所以「审批通过」必须落到白名单表。
> 注册平台已新增 QQ 字段（注册时提交，落 `users.qq`），内测审批通过时后端会**自动把
> 「QQ + 离线服游戏 ID」同步成白名单条目**，运营不必再手工录入。详见 §3.2 与 §5.3。

---

## 1. 背景与目标

客户端 Mod 在玩家**启动游戏、出现主界面**时弹出 QQ 号验证窗口。玩家提交后，Mod 以 **HTTP GET** 调用后端接口校验内测资格，并根据响应三选一：

| 判定 | 客户端行为 |
| --- | --- |
| **通过** | 写入本地 `config/northstar/verification.json`，放行进入游戏 |
| **明确未通过** | 生成崩溃报告并**退出游戏**，日志文案固定为「因northstar 北极战区：您的内测验证未通过，请重试」 |
| **服务不可用** | 停在验证窗口红字提示原因，**玩家可重试，不崩溃** |

### 1.1 为什么不用 UUID

| 原因 | 说明 |
| --- | --- |
| 离线 UUID 不稳定 | 离线模式下客户端 UUID 由启动器生成，同一个玩家换启动器 / 换「正版登录」开关 / 重装启动器都会变 |
| 客户端与服务端不一致 | 服务端按 `OfflinePlayer:<name>` 派生 MD5 UUID，客户端未必按同一规则派生，两边可能对不上 |
| 无法作为身份依据 | 既然每次都可能变，用它匹配白名单就会「今天能进、明天被拒」，属于**必然发生的误判** |

因此：**客户端不再上报 UUID，后端不再接收 UUID，白名单也不再记录 UUID。**

---

## 2. 通用约定

| 项 | 约定 |
| --- | --- |
| 传输协议 | 生产环境必须 HTTPS（客户端也允许 `http://`，仅用于内网联调） |
| 请求方法 | GET |
| 字符编码 | 一律 UTF-8。请求参数 URL 编码，**响应体必须为 UTF-8** |
| 响应 Content-Type | `application/json` |
| 幂等性 | 校验接口幂等：同一玩家重复调用结果一致（仅追加日志，无业务副作用） |

> **编码提醒**：客户端读取响应体时**强制按 UTF-8 解码**，不参考响应头 `charset`。返回 GBK 会导致中文乱码。

---

## 3. 接口 A：内测资格校验（核心，已实现）

### 3.1 请求

```
GET /api/beta/verify?qq={qq}&name={name}
```

生产示例：

```
https://<后端域名>/api/beta/verify?qq=123456789&name=Steve
```

客户端配置项 `verifyUrl` 填**完整地址**（含 `/api/beta/verify`）。

#### 请求参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `qq` | string | 是 | 玩家提交的 QQ 号。客户端已规范化：**仅保留半角数字**（全角 `０-９` 转半角，其余字符丢弃），并通过默认正则 `^[1-9]\d{4,10}$` 校验（5–11 位、不以 0 开头） |
| `name` | string | 是 | **游戏ID**（Minecraft 玩家名）。离线模式下即玩家在启动器里填的登录名，与服务端看到的玩家名一致。可能含中文、空格、下划线，已 URL 编码。大小写不敏感 |
| ~~`uuid`~~ | — | — | **已废弃、不再使用**。客户端已停止上报；若旧版客户端仍传，Spring 会直接忽略该参数，不影响判定 |

> 服务端对 `qq` 使用**与客户端完全相同的正则**（`northstar.verify.qq-pattern`），因此不会出现「客户端放行、服务端拒绝」的错位。
>
> `name` 服务端**只做非空与长度校验，不限制字符集**——某些离线服务端允许中文或特殊字符的玩家名，限制字符集会把合法玩家挡在门外。

#### 请求头（客户端固定发送）

| Header | 值 | 用途 |
| --- | --- | --- |
| `Accept` | `application/json` | |
| `User-Agent` | `NorthStarClientVerification/1.0` | 识别来源、排查问题 |

#### 超时与重定向

| 项 | 值 |
| --- | --- |
| 单次请求超时 | 默认 **8000 ms**（配置项 `httpTimeoutMs`，1000–60000） |
| 连接超时 | 10 s（固定） |
| 重定向 | 跟随普通重定向；**不跟随 HTTPS → HTTP 降级** |

> 服务端应在 **1 s 内**返回（P99）。超时会被判为「服务不可用」。

---

### 3.2 身份判定规则（**核心业务逻辑**）

服务端按以下顺序判定，任一步失败即返回「明确未通过」：

| 步骤 | 条件 | 结果 |
| --- | --- | --- |
| 1 | `qq` 不匹配 `^[1-9]\d{4,10}$` | HTTP **400**（可重试，不崩溃） |
| 2 | `name` 为空 | HTTP **400**（可重试，不崩溃） |
| 3 | 该来源 IP 超过限流阈值 | HTTP **429**（可重试，不崩溃） |
| 4 | `qq` 在白名单中**完全没有记录** | 200 + `success:false`（**未通过，崩溃**） |
| 5 | 有记录，但**全部**处于禁用 / 已过期 | 200 + `success:false`（**未通过，崩溃**） |
| 6 | 存在可用记录且其 `mcId` **与上报游戏ID 一致**（忽略大小写） | ✅ **通过**，`data.matchedBy = "bound"` |
| 7 | 存在可用记录且其 `mcId` **为空**（只绑 QQ、不限定游戏ID） | ✅ **通过**，`data.matchedBy = "unbound"`<br>（该分支可用 `northstar.verify.require-mc-id=true` 关闭） |
| 8 | 有可用记录，但都绑定了别的游戏ID | 200 + `success:false`（**未通过，崩溃**） |
| 9 | 任何内部异常（数据库等） | HTTP **5xx**（可重试，不崩溃） |

#### `qq` 从哪来

| 项 | 来源 |
| --- | --- |
| `name`（游戏ID） | 客户端 Mod 自动读取本机登录名，玩家改不了——它就是玩家启动器里用的那个名字 |
| `qq` | 玩家在验证弹窗里手动输入 |

服务端侧，白名单条目的 QQ **不再由运营手工维护**：注册平台在注册时就要求玩家提交 QQ 与离线服
游戏 ID（`POST /api/auth/register` 的 `qq` / `mcId`），内测审批通过后由后端自动同步成白名单条目。
后台手工录入 / 批量导入仍然保留，作为补充通道（详见 §5.3「白名单条目的来源」）。

> **v3.2：QQ 一个账号只允许绑定一次，绑定后不可更改。**
> 这是必要约束而非体验取舍——白名单以 QQ 为键，若允许随意改绑，玩家就能把已获批的内测资格
> 转手给别人，白名单与账号的绑定关系也就没有意义了。
>
> | 场景 | 行为 |
> | --- | --- |
> | 注册时填了 QQ | 即完成绑定，之后**任何入口都改不了** |
> | 注册时留空（或 v3.1 之前的老账号） | 可在平台**「账号设置」页补绑一次**（`PUT /api/auth/profile`） |
> | 已绑定后再提交不同的 QQ | 返回 **400**「QQ 号已绑定，绑定后不可更改；如需更正请联系管理员」 |
> | 已绑定后提交同一个 QQ | 幂等成功，不算改动 |
> | 提交空值 | 不生效，**不能**借此解绑 |
> | 填错了 QQ | 只能由管理员在后台「编辑玩家」强制改写（会记日志）；清空即解绑，玩家可重绑一次 |
>
> 这不影响客户端：校验接口仍然只认 `qq` + `name` 两个参数，玩家进游戏时填的 QQ
> 必须与账号绑定的那个一致。

#### 「明确未通过」时的 `msg` 文案

| 场景 | `msg` |
| --- | --- |
| QQ 不在白名单 | `该 QQ 未获得内测资格` |
| 资格被禁用 | `该 QQ 的资格已被禁用` |
| 资格已过期 | `该 QQ 的资格已过期` |
| 绑定了别的游戏ID | `游戏ID「Alex」与白名单绑定的不一致（已绑定：Steve）` |
| 要求绑定但未绑定 | `该 QQ 未登记游戏ID，请联系管理员补充绑定` |

> 这些 `msg` **不会**显示给玩家（客户端有自己固定的崩溃文案），只出现在崩溃日志的「响应内容」和管理后台的校验日志里，用于客服排查。

---

### 3.3 响应定义

服务端返回**专用响应体**（`BetaVerifyResponse`），**不使用**项目通用的 `ApiResponse` 包装。

#### ✅ 通过

```json
{
  "success": true,
  "code": 0,
  "msg": "验证通过",
  "data": {
    "qq": "123456789",
    "nickname": "明明",
    "gameId": "Steve",
    "matchedBy": "bound",
    "type": "北极战区一期内测",
    "expire": "2026-12-31",
    "playerName": "Steve"
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `data.gameId` | 白名单里**绑定**的游戏ID；未绑定时为 `null` |
| `data.matchedBy` | 命中方式：`bound`（精确命中绑定）/ `unbound`（未绑定游戏ID，任意名字放行） |
| `data.expire` | 到期日 `yyyy-MM-dd`，永不过期时为 `null` |
| `data.type` | 取自白名单条目的 `remark` 字段（未填写时为「标准内测资格」） |
| `data.nickname` | 后台填写的昵称，仅展示 |
| `data.playerName` | 客户端上报的游戏ID 原样回显，便于比对 |

> `data` 只作展示 / 排查用，客户端**不做**任何字段校验。

#### ❌ 明确未通过（**会导致玩家客户端崩溃退出**）

```json
{ "success": false, "code": 1001, "msg": "该 QQ 未获得内测资格", "data": null }
```

`msg` 取值见 §3.2 的文案表。

#### ⚠️ 服务不可用（玩家可重试，不崩溃）

必须使用**非 2xx** 状态码：

| HTTP | 触发场景 | 响应体 |
| --- | --- | --- |
| `400` | `qq` 格式不合法 / `name` 为空 | `{"success":false,"code":4000,"msg":"QQ 号格式无效"}` |
| `429` | 单 IP 超过限流阈值 | `{"success":false,"code":4290,"msg":"请求过于频繁，请稍后重试"}` |
| `5xx` | 数据库/内部异常 | `{"success":false,"code":5000,"msg":"校验服务暂时不可用：..."}` |

> 客户端**只按状态码判断**，非 2xx 时完全不解析响应体，因此这些响应体内容不会导致崩溃。

---

### 3.4 客户端判定规则（**必须保持一致**）

客户端判定顺序（源码 `RemoteVerifier.interpret` / `readSuccessFlag`）：

| 步骤 | 条件 | 客户端判定 |
| --- | --- | --- |
| 1 | HTTP 状态码**非 2xx** | 服务不可用（不解析响应体） |
| 2 | 响应体为空，或**不是 JSON 对象**（数组/字符串/数字/HTML） | 服务不可用 |
| 3 | 存在 `success` 字段 | 见下方细化 |
| 4 | 不存在 `success`，但存在 `code` | 见下方细化 |
| 5 | 两个字段都不存在 | 服务不可用 |

**`success` 字段：**

| 值 | 判定 |
| --- | --- |
| JSON 布尔 `true` | ✅ 通过 |
| JSON 布尔 `false` | ❌ 未通过（崩溃） |
| 字符串 `"true"`（忽略大小写） | ✅ 通过 |
| 其它字符串（`"false"`、`"1"`、`"yes"`） | ❌ **未通过（崩溃）** |
| 数字（`1`、`0`） | ⚠️ 服务不可用 |

**`code` 字段：**（v2.0 起 `0` 与 `200` 都算通过）

| 值 | 判定 |
| --- | --- |
| 数字 `0` | ✅ 通过 |
| 数字 `200` | ✅ 通过（**兼容后端既有 `ApiResponse` 用 200 表示成功的约定**） |
| 其它整数（`1`、`404`、`1001` …） | ❌ 未通过（崩溃） |
| 可解析为整数的字符串 `"0"` / `"200"` | ✅ 通过 |
| 无法解析为整数的字符串（`"OK"`） | ⚠️ 服务不可用 |
| 布尔 / null / 对象 | ⚠️ 服务不可用 |

> **优先级**：`success` 优先于 `code`。两者同时存在时以 `success` 为准。
> 服务端当前同时输出两者，`success` 命中规则 3，`code` 作为兜底。

#### 🚨 必须避开的三个坑

1. **服务异常绝不能返回 `200` + `success:false`** —— 会被判「明确未通过」，直接把玩家踢出游戏并生成崩溃日志。**服务异常一律用非 2xx。**
2. **`success` 必须用 JSON 布尔**。写数字 `1` → 判「服务不可用」；写字符串 `"1"` → 判「未通过」（崩溃）。
3. **HTTP 200 必须携带合法 JSON 对象**。返回 HTML 错误页、空响应体、JSON 数组都会被判「服务不可用」。

---

## 4. 接口 B：白名单管理（已实现，供运营后台）

整组接口挂在 `/api/admin/**` 下，由 `SecurityConfig` 统一要求 `ROLE_ADMIN`，**不会匿名开放**。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/beta/whitelist?page=1&size=20&keyword=&status=` | 分页查询（`status` 传 `1`/`0`） |
| POST | `/api/admin/beta/whitelist` | 新增一条「QQ + 游戏ID」授权 |
| PUT | `/api/admin/beta/whitelist/{id}` | 修改（只改传入的字段） |
| DELETE | `/api/admin/beta/whitelist/{id}` | 删除 / 剔除资格 |
| POST | `/api/admin/beta/whitelist/import` | JSON 批量导入 |
| POST | `/api/admin/beta/whitelist/import-csv?replace=false` | 纯文本批量导入 |
| GET | `/api/admin/beta/whitelist/export` | 导出 CSV（带 UTF-8 BOM） |
| GET | `/api/admin/beta/verify-logs?page=1&size=20&keyword=&result=&showQq=false` | 查询校验日志 |

### 4.1 新增 / 修改

请求体（字段全部可选，新增时 `qq` 必填；修改时只传要改的字段）：

```json
{
  "qq": "123456789",
  "mcId": "Steve",
  "nickname": "明明",
  "remark": "北极战区一期内测",
  "status": 1,
  "expireAt": "2026-12-31"
}
```

| 字段 | 校验 |
| --- | --- |
| `qq` | 必填，纯数字 5–11 位且不以 0 开头。**不再全局唯一**，唯一键是 `(qq, mcId)` 组合 |
| `mcId` | **绑定的游戏ID**，`^[a-zA-Z0-9_]{3,16}$`。**参与校验**，必须与玩家实际登录名一致（忽略大小写）。**留空表示不限定游戏ID** |
| `nickname` | 可选，仅展示 |
| `remark` | 可选，会作为校验通过时 `data.type` 返回 |
| `status` | 只能为 `1`（启用）或 `0`（禁用），默认 `1` |
| `expireAt` | 支持 `yyyy-MM-dd`（取当日 23:59:59）、`yyyy-MM-dd HH:mm`、`yyyy-MM-dd HH:mm:ss`、`yyyy-MM-ddTHH:mm:ss`，或 10/13 位时间戳；传空字符串 = 永不过期 |

**唯一性规则**：同一个 QQ **可以绑定多个游戏ID**（例如测试号绑小号），但**不能重复绑定同一个**（忽略大小写）。重复时返回 `400`，`message` 形如 `该 QQ 已绑定游戏ID「Steve」`；已存在一条「不限定游戏ID」的记录时再新增一条空绑定，报 `该 QQ 已有一条「不限定游戏ID」的白名单记录`。

> ⚠️ **`mcId` 留空的取舍**：留空 = 只要知道这个 QQ 就能进，**不推荐长期使用**。若要收紧，把 `northstar.verify.require-mc-id` 置为 `true`，未绑定游戏ID 的条目将一律判为未通过。

### 4.2 批量导入

**JSON 方式** `POST /api/admin/beta/whitelist/import`

```json
{
  "replace": false,
  "items": [
    { "qq": "123456789", "mcId": "Steve", "nickname": "明明", "remark": "一期内测", "expireAt": "2026-12-31" },
    { "qq": "987654321", "mcId": "Alex" },
    { "qq": "13600000006" }
  ]
}
```

**纯文本方式** `POST /api/admin/beta/whitelist/import-csv?replace=false`
`Content-Type: text/plain;charset=UTF-8`

```csv
qq,gameId,nickname,remark,expireAt
# 一期内测名单
123456789,Steve,明明,北极战区一期内测,2026-12-31
987654321,Alex,老王,二期内测,
13600000006,,小李,只绑QQ不限定游戏ID,
```

- 列顺序固定为 `qq,gameId,nickname,remark,expireAt`，后面的列可省略
- **第 2 列是游戏ID**（对应接口字段 `mcId`）。留空 = 不限定游戏ID
- 分隔符支持**逗号与制表符**；首行若为 `qq,...` 自动当表头跳过；`#` 开头为注释；空行跳过
- 单行出错（如游戏ID 格式非法）**只计入失败明细，不影响其它行**
- `replace=true` 会**先清空全部白名单再导入**（危险，默认 `false`）

响应：

```json
{
  "code": 200,
  "message": "导入完成：新增 2，更新 0，跳过 0，失败 0",
  "data": { "created": 2, "updated": 0, "skipped": 0, "failed": 0, "errors": [] }
}
```

> 已存在的 `(qq, gameId)` 组合会被**覆盖更新**（`updated` 计数），不会报错。同一个 QQ 的不同游戏ID 会各自新增一条。

### 4.3 分页响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 128,
    "page": 1,
    "size": 20,
    "list": [
      {
        "id": 1, "qq": "123456789", "mcId": "Steve", "gameIdBound": true,
        "nickname": "明明", "remark": "北极战区一期内测",
        "status": 1, "expireAt": "2026-12-31 23:59", "expireDate": "2026-12-31",
        "active": true, "createdAt": "2026-09-24 11:20", "updatedAt": "2026-09-24 11:20",
        "createdBy": "JeffreyMing"
      }
    ]
  }
}
```

| 派生字段 | 说明 |
| --- | --- |
| `active` | 服务端算出的「启用且未过期」，前端可直接用它决定是否显示为可用 |
| `gameIdBound` | `false` 表示该条目不限定游戏ID（任何名字都能通过），**后台列表建议高亮提示**，避免运营忘记填游戏ID |

> 后台列表页建议对 `gameIdBound = false` 的行加醒目提示（黄色标签 / 警告图标）。

### 4.4 校验日志

日志默认**脱敏**：`qq` 返回 `123****89`。需要精确排查时加 `showQq=true`（仍按原始 QQ 过滤，因此用完整 QQ 搜索也能命中）。

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 2, "page": 1, "size": 20,
    "list": [
      {
        "id": 2, "qq": "123****89", "playerName": "Alex", "ip": "203.0.113.9",
        "userAgent": "NorthStarClientVerification/1.0",
        "result": "reject", "matchedBy": null, "httpStatus": 200, "costMs": 12,
        "reason": "游戏ID 与绑定不一致（已绑定 Steve）", "createdAt": "2026-09-24 11:22:03"
      }
    ]
  }
}
```

| 字段 | 取值 |
| --- | --- |
| `result` | `pass` / `reject`（玩家会崩溃）/ `error`（玩家可重试） |
| `matchedBy` | 仅 `pass` 时有值：`bound`（精确命中绑定）/ `unbound`（未绑定游戏ID 放行） |

> `keyword` 可匹配 `qq`、游戏ID、来源 IP。

---

## 5. 服务端处理逻辑（实际实现）

```
GET /api/beta/verify?qq=&name=

1. qq 不匹配 ^[1-9]\d{4,10}$        -> 400 + code 4000（客户端可重试）
2. name 为空                        -> 400 + code 4000（客户端可重试）
3. 该 IP 超过限流阈值（默认 60/分钟） -> 429 + code 4290（客户端可重试）
4. 查 northstar_beta_whitelist where qq = ?
   4.1 无任何记录                    -> 200 + success:false code 1001
   4.2 有记录但全部禁用 / 已过期       -> 200 + success:false code 1001
   4.3 有可用记录且 mcId 与 name 一致  -> 200 + success:true  code 0（matchedBy=bound）
   4.4 有可用记录且 mcId 为空          -> 200 + success:true  code 0（matchedBy=unbound）
       （require-mc-id=true 时此分支关闭，直接走 4.5）
   4.5 有可用记录但绑定了别的游戏ID     -> 200 + success:false code 1001
5. 任何内部异常                      -> 500 + code 5000（客户端可重试）
6. 每个分支都写一条 northstar_verify_log
```

### 5.1 相关配置项（`application.properties`）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `northstar.verify.qq-pattern` | `^[1-9]\d{4,10}$` | 与客户端 `qqPattern` 保持一致 |
| `northstar.verify.rate-limit-per-minute` | `60` | 每来源 IP 每分钟允许次数，`<=0` 表示不限流 |
| `northstar.verify.require-mc-id` | `false` | **是否强制要求白名单条目已绑定游戏ID**。`false`：未绑定的 QQ 用任意游戏ID 都能通过；`true`：未绑定的一律判未通过 |

环境变量覆盖：`VERIFY_QQ_PATTERN`、`VERIFY_RATE_LIMIT_PER_MINUTE`、`VERIFY_REQUIRE_MC_ID`。

> ⚠️ 把 `require-mc-id` 改成 `true` 之前，**务必确认白名单里每一条都填了 `mcId`**，否则这些 QQ 的玩家会全部崩溃退出。

> 限流是**单机内存**滑动窗口，重启即清零、多实例不共享。当前部署规模足够；若将来多实例，建议改用 Redis（项目已引入 `spring-boot-starter-data-redis`）。

### 5.2 来源 IP 解析

服务端按 `X-Forwarded-For`（取第一个）→ `X-Real-IP` → `remoteAddr` 的顺序取客户端 IP。
生产环境前面有 Nginx / OpenResty 时，**务必确保反代写入了 `X-Forwarded-For`**，否则限流会把所有玩家当成同一个 IP。

### 5.3 数据库表

`ddl-auto=update`，服务启动时自动建表。

**`northstar_beta_whitelist`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint PK auto_increment | |
| `qq` | varchar(16) NOT NULL | QQ 号，**不再单独唯一** |
| `mc_id` | varchar(64) NULL | **绑定的游戏ID（参与校验）**；NULL 表示不限定游戏ID |
| `nickname` | varchar(64) | 昵称 / 备注名（仅展示） |
| `remark` | varchar(255) | 备注，会作为 `data.type` 返回 |
| `status` | int NOT NULL | 1 启用 / 0 禁用 |
| `expire_at` | datetime NULL | 到期时间，NULL 表示永不过期 |
| `created_at` / `updated_at` | datetime | |
| `created_by` | varchar(64) | 操作人（取自 JWT 用户名） |
| `source` | varchar(16) | 条目来源：`manual` 人工录入/导入；`account` 账号审批自动同步。历史数据为 NULL 时按 `manual` 处理 |
| — | **UNIQUE KEY `uk_beta_whitelist_qq_mcid` (`qq`, `mc_id`)** | 唯一键 |

> **v3.0 移除了 `player_uuid` 列**（离线模式下无意义）。
> **v3.1 新增了 `source` 列**（`ddl-auto=update` 会自动加上，无需手工 DDL）。

#### 白名单条目的来源（与注册平台的关系）

服务端不启用 MC 白名单、也不校验 UUID，判定完全在后端做。因此「审批通过」必须落到白名单表，
否则玩家会被判「未通过」而客户端崩溃。白名单有两类来源，靠 `source` 区分：

| `source` | 来源 | 谁维护 |
| --- | --- | --- |
| `account` | 玩家在注册平台提交的 QQ + 离线服游戏 ID（`users.qq` / `users.mc_id`），内测审批通过后由后端自动同步 | 系统；重新审批会按最新值重建 |
| `manual` | 运营在后台手工新增 / 批量导入 | 运营 |

自动同步的幂等规则：

1. 已存在完全相同的 `(qq, mcId)` 条目 → **保持原样**，连运营改过的启用状态与到期时间都不动
2. 否则删除该 QQ 下所有 `source=account` 的旧条目，按当前值重建一条「启用、永不过期」的条目
   ——玩家改了游戏ID 或 QQ 后不会留下旧的通行证
3. `manual` 条目**永不**被系统删除

触发时机：审核申请通过 / 后台直接发放 / 管理员修改账号（QQ、游戏ID、内测状态）时，
**同步失败会让整个审批操作失败并报错**（提示先去补填 QQ），
避免出现「后台显示已批准、玩家进游戏却被判未通过而崩溃」这种状态。
注册或改绑时「认领」已有资格属于自动流程，缺 QQ 只记警告，不会打断玩家注册。

> `users` 表因此新增了 `qq` 列（varchar(16)，可空）。存量账号可由管理员在后台「编辑玩家」补填，
> 或玩家自己调 `PUT /api/auth/profile`。**没有 QQ 的账号无法获批内测。**

> ⚠️ 这个「自动同步」只解决**放行**问题，不改变 §7 的风险性质：玩家名与 QQ 都是自报的，
> 接口仍可被绕过。

**`northstar_verify_log`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint PK auto_increment | |
| `qq` | varchar(16) | 提交的 QQ（后台按需脱敏展示） |
| `player_name` | varchar(64) | 客户端上报的**游戏ID** |
| `ip` | varchar(64) | 来源 IP |
| `user_agent` | varchar(255) | 来源 UA |
| `result` | varchar(16) | `pass` / `reject` / `error` |
| `matched_by` | varchar(16) | `pass` 时的命中方式：`bound` / `unbound` |
| `http_status` | int | 返回给客户端的状态码 |
| `cost_ms` | bigint | 处理耗时 |
| `reason` | varchar(128) | 未通过 / 异常原因 |
| `created_at` | datetime | |

**时间字段说明**：与项目既有表保持一致，使用 `datetime`（`LocalDateTime`），接口中以
`yyyy-MM-dd HH:mm` / `yyyy-MM-dd HH:mm:ss` 字符串输出，**不再是毫秒时间戳**（v1.0 的建议写法已作废）。

> 日志表增长较快，后台日志查询固定只看**最近 1000 条**再过滤分页；建议按 90 天做归档清理。

#### 🔧 v3.0 升级注意（数据库）

`ddl-auto=update` **不会删除旧索引、也不会删除旧列**。若你在 v3.0 之前已经启动过服务、建过表，需要手工处理：

```sql
-- 1) 查看现有的唯一索引（v2.0 时代 qq 上有一个单列唯一约束）
SHOW INDEX FROM northstar_beta_whitelist;

-- 2) 删掉 qq 上的单列唯一索引（名字一般是 qq，或 Hibernate 生成的哈希串）
--    不删的话，同一个 QQ 绑定第二个游戏ID 会被数据库拒绝
ALTER TABLE northstar_beta_whitelist DROP INDEX `qq`;

-- 3) player_uuid 列已不再使用，可保留（无害）或删除
ALTER TABLE northstar_beta_whitelist DROP COLUMN player_uuid;
ALTER TABLE northstar_verify_log     DROP COLUMN player_uuid;
```

> **尚未上线过的话最简单**：直接 `DROP TABLE northstar_beta_whitelist; DROP TABLE northstar_verify_log;`
> 让服务重建即可（反正白名单本来也要重新导入）。

---

## 6. 联调与自测

### 6.1 curl 验证契约

```bash
BASE="http://127.0.0.1:8080/api/beta/verify"

# 通过（QQ 在名单里，且 name 与绑定的游戏ID 一致）
curl -i "$BASE?qq=123456789&name=Steve"

# 未通过：QQ 不在名单里
curl -i "$BASE?qq=123450000&name=Steve"

# 未通过：QQ 在名单里，但游戏ID 与绑定不一致
curl -i "$BASE?qq=123456789&name=Alex"

# QQ 格式非法 -> 400（可重试）
curl -i "$BASE?qq=abc&name=Steve"

# 缺 name -> 400（可重试）
curl -i "$BASE?qq=123456789"

# 中文游戏名
curl -i "$BASE?qq=123456789&name=%E6%98%8E%E6%98%8E"
```

期望：第 1 条 `HTTP 200` + `"success":true,"code":0`；第 2、3 条 `HTTP 200` + `"success":false,"code":1001`；第 4、5 条 `HTTP 400`。

### 6.2 客户端侧配置

编辑 `config/northstarclientverification-common.toml`：

```toml
# 线上（前端站点与接口同域，见 northstar_frontend/deploy/README.md）
verifyUrl = "https://northstar.mingpixel.net/api/beta/verify"
# 本地联调时用这个
# verifyUrl = "http://127.0.0.1:8080/api/beta/verify"
debugLog = true
```

`verifyUrl` **留空时客户端会直接放行（`SKIPPED`）**，所以正式发版必须填上，否则等于没开校验。

`debugLog = true` 后，`logs/latest.log` 会打印每次请求的完整 URL 与判定结果。

> 想反复触发弹窗：删除 `config/northstar/verification.json` 后重启游戏。

> 本地 `verification.json` 的 `players` 键是**游戏ID（小写）**，例如：
> ```json
> { "version": 2, "players": { "steve": { "name": "Steve", "qq": "123456789",
>   "firstVerifiedAt": 1758680000000, "lastVerifiedAt": 1758680000000 } } }
> ```
> v1 版按 UUID 索引的旧文件会在首次载入时**自动迁移**，无需手工处理。

---

## 7. 安全与风险提示

1. **本验证属于客户端行为，可被绕过。** 玩家可以改配置、抓包改写响应、直连自建服务、删除 `verification.json`，甚至反编译 Mod。因此本接口适用于**内测资格分发与数据统计**，**不能作为多人服务器的安全边界**。若要真正防止未授权进入，必须在**服务端**校验（服务端 Mod / 插件 + 登录握手）。
2. **接口无鉴权令牌。** 任何人知道地址就能查询「某 QQ+游戏ID 是否有资格」。好在响应只泄露"有/无资格"，不返回玩家隐私。若需更强防护，下一版可引入 HMAC-SHA256 签名：对 `qq|name|timestamp` 用共享密钥签名（**注意：签名里不含 UUID**），服务端校验签名与时间窗（±5 分钟）。此方案需 Mod 侧配合改动。
3. **「游戏ID」比 UUID 更弱。** 离线模式下玩家名是**玩家自己可改**的，因此「QQ + 游戏ID」只能证明"知道这个 QQ 且用了这个名字"，不等于强身份。若要绑定真正的人，建议在人工发号环节让申请人提供游戏ID 并由运营核对。
4. **QQ 号是个人信息。** 接口为 GET，QQ 会进入 Nginx / CDN 访问日志。**建议在反代日志中对 `qq` 参数脱敏**（如 `123****89`）；管理接口默认已脱敏。并请在隐私政策中说明采集 QQ 号的用途与保留期限（主体：抚州明像素网络科技有限公司）。
5. **限流已实现**（默认 60 次/分钟/IP），并针对 `qq` 做长度与字符白名单校验。
6. **管理接口不得暴露公网**，当前已要求 `ROLE_ADMIN`；建议再加 IP 白名单。

---

## 8. 客户端配置项对照表

配置文件：`config/northstarclientverification-common.toml`

| 配置项 | 默认值 | 取值范围 | 说明 |
| --- | --- | --- | --- |
| `enableVerification` | `true` | 布尔 | 是否在主界面弹窗 |
| `verifyUrl` | `""` | 字符串 | **接口 A 完整地址**；留空则跳过远端校验、本地直接放行 |
| `httpTimeoutMs` | `8000` | 1000–60000 | 请求超时（毫秒） |
| `reverifyOnLaunch` | `false` | 布尔 | 本地已有记录时是否仍重新校验 |
| `crashOnReject` | `true` | 布尔 | 「明确未通过」时是否崩溃退出 |
| `openDelayTicks` | `20` | 0–200 | 主界面出现后延迟多少刻弹窗（20 刻 = 1 秒） |
| `blockEscape` | `true` | 布尔 | 验证完成前是否禁止 ESC 关闭弹窗 |
| `qqPattern` | `^[1-9]\d{4,10}$` | 正则 | QQ 号校验规则（需与服务端一致） |
| `debugLog` | `false` | 布尔 | 是否输出调试日志 |

> `verifyUrl` 只接受 `http://` / `https://` 开头，其它协议直接判为服务不可用、不发请求。

---

## 9. 验收对照表

客户端与服务端的自动化测试均已覆盖以下条目：

| 检查项 | 覆盖方式 |
| --- | --- |
| 接口为 GET，参数名严格为 `qq` / `name`（**不含 `uuid`**） | `VerifyIT` URL 拼接断言（含"不含 uuid"反向断言） |
| 旧版客户端多传 `uuid` 时不影响判定 | `BetaVerifyEndToEndTest` 实发带 `uuid` 的请求 |
| 通过时返回 `200` + `success`（JSON 布尔）+ `code:0` | `BetaVerifyContractTest` 序列化断言 + `VerifyIT` |
| 未通过时返回 `200` + `success:false` + `code:1001` | 同上 |
| `code:200` 也能被判为通过（兼容 `ApiResponse`） | `VerifyIT` `/code200` 用例 |
| 服务异常一律非 2xx，且客户端**不崩溃** | `VerifyIT` 400/429/500 三用例 |
| 响应体 UTF-8、`Content-Type: application/json` | 端到端实测 |
| 中文 / 含空格游戏名正确传递与解码 | `VerifyIT` 编码断言 + `BetaVerifyEndToEndTest` |
| **游戏ID 与绑定一致 → 通过** | 服务层测试 + 端到端实测 |
| **游戏ID 与绑定不一致 → 明确未通过** | 服务层测试 + 端到端实测 |
| **游戏ID 大小写不敏感** | 服务层测试 + 端到端实测 |
| **未绑定游戏ID → 默认放行；`require-mc-id=true` 时拒绝** | `BetaWhitelistServiceTest` 两条用例 |
| **同一 QQ 可绑多个游戏ID，且各自独立命中** | 服务层测试 + 端到端实测 |
| 缺 `name` → 400（可重试，**不崩溃**） | 服务层测试 + 端到端实测 |
| 白名单禁用 / 过期 / 不存在 → 明确未通过 | `BetaWhitelistServiceTest` |
| 已记录校验日志（含 `matchedBy`）并按需脱敏 | `BetaWhitelistServiceTest` |
| 按 IP 限流（429 可重试） | `BetaWhitelistServiceTest` |
| 管理接口需 `ROLE_ADMIN` | `SecurityConfig` + 端到端实测 |

> **上线前务必确认白名单已导入。** 若白名单为空，**所有玩家都会被判「未通过」并崩溃** —— 属于重大线上事故。可先只给 1 个测试 QQ（并填好游戏ID）发放资格，确认能正常进入后再批量导入。

---

## 10. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v1.0 | 2026-09-24 | 首版，接口 A/B 为待开发提案；`code` 只认 `0`；时间戳用毫秒 |
| v2.0 | 2026-09-24 | 接口已在 `northstar_backend` 落地；`code` 认 `0`/`200`；时间改为 `datetime` 字符串；新增 429 限流、日志脱敏、`import-csv` |
| **v3.2** | 2026-09-24 | **QQ 一个账号只允许绑定一次，绑定后不可更改**（防止内测资格被转手）。注册即绑定；老账号可在平台新增的「账号设置」页补绑一次；已绑定后改绑返回 400；空值不解绑；填错只能由管理员后台强制改写（留日志）。接口契约未变 |
| **v3.1** | 2026-09-24 | 打通注册平台与白名单：`users` 新增 `qq`，注册接口收集 QQ；白名单新增 `source` 列；内测审批通过（审核 / 后台发放 / 管理员改账号）时**自动同步**白名单条目，未填 QQ 则阻断审批并提示补填 |
| **v3.0** | 2026-09-24 | **UUID 彻底移出验证流程**（离线模式 UUID 不稳定）：请求参数减为 `qq`+`name`；白名单唯一键改为 `(qq, mcId)`，`mcId` 由"仅记录"变为**参与校验**；新增 `northstar.verify.require-mc-id`；CSV 列顺序改为 `qq,gameId,nickname,remark,expireAt`；日志新增 `matchedBy`、移除 `playerUuid` |

---

## 附录 A：客户端判定逻辑参考实现

与 `RemoteVerifier.readSuccessFlag` 完全一致，可用于后端侧自测：

```java
private static Boolean readSuccessFlag(String body) {
    if (body == null || body.isBlank()) {
        return null;                                  // -> 服务不可用
    }
    JsonElement root = JsonParser.parseString(body.trim());
    if (!root.isJsonObject()) {
        return null;                                  // -> 服务不可用
    }
    JsonObject object = root.getAsJsonObject();

    if (object.has("success") && object.get("success").isJsonPrimitive()) {
        JsonPrimitive value = object.getAsJsonPrimitive("success");
        if (value.isBoolean()) {
            return value.getAsBoolean();              // true -> 通过, false -> 未通过
        }
        if (value.isString()) {
            return "true".equalsIgnoreCase(value.getAsString());  // 其它字符串 -> 未通过
        }
        return null;                                  // 数字 -> 服务不可用
    }

    if (object.has("code") && object.get("code").isJsonPrimitive()) {
        JsonPrimitive value = object.getAsJsonPrimitive("code");
        if (value.isNumber() || value.isString()) {
            try {
                int code = value.getAsInt();
                return code == 0 || code == 200;      // v2.0：0 与 200 都算通过
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
    return null;                                      // -> 服务不可用
}
```

返回 `true` → 通过；`false` → 明确未通过（客户端崩溃）；`null` → 服务不可用（玩家可重试）。

## 附录 B：客户端 URL 构造规则

```
base = 配置项 verifyUrl（已去首尾空白）
分隔符 = base 中已含 '?' ? '&' : '?'
url = base + 分隔符 + "qq=" + urlencode(qq)
                     + "&name=" + urlencode(name)
```

`urlencode` 为 UTF-8 URL 编码，空格编码为 `+`，中文编码为 `%XX`。
因此 `verifyUrl` **可以自带查询参数**，例如 `...verify?token=abc`，客户端会自动用 `&` 追加。

> **v3.0 起不再追加 `&uuid=...`。** 离线服务器的 UUID 不稳定，不是有效身份依据（见 §1.1）。

## 附录 C：游戏ID 判定的参考实现（服务端）

```java
List<BetaWhitelist> rows = repo.findAllByQqOrderByCreatedAtAsc(qq);
if (rows.isEmpty())                                   return reject("该 QQ 未获得内测资格");

List<BetaWhitelist> usable = rows.stream().filter(e -> e.isUsable(now)).toList();
if (usable.isEmpty())                                 return reject("该 QQ 的资格已被禁用 / 已过期");

// 1) 优先精确命中绑定的游戏ID（忽略大小写）
for (BetaWhitelist e : usable) {
    if (e.matchesGameId(name))                        return pass(e, "bound");
}
// 2) 未绑定游戏ID 的条目：默认放行（require-mc-id=true 时跳过）
if (!requireGameId) {
    for (BetaWhitelist e : usable) {
        if (e.getBoundGameId() == null)               return pass(e, "unbound");
    }
}
// 3) 都绑了别的游戏ID
return reject("游戏ID 与白名单绑定的不一致");
```
