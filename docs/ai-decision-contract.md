# AI 短决策与队列补充契约

客户端已接入 DeepSeek/OpenAI 的纯文本策略请求，**默认关闭，需显式启用并保存**。
`StrategyPrompt` 负责固定规则、紧凑候选和严格输出解析；`AiHttpClient` 负责有界 HTTP；
`AiStrategySession` 与控制器在游戏线程维护请求归属、60 秒间隔、会话用量和最终入队检查。
接口实现、夹具验证与真实付费 API 验证是不同范围，本文不表示某个账号或模型已经调用成功。

## 固定规则与动态数据

提示词版本为 `StrategyPrompt.VERSION = 1`，固定原文保存在 `StrategyPrompt.SYSTEM_PROMPT`。
WebUI 通过已鉴权的状态接口只读展示规则、版本和动态输入；打开预览不触发模型请求。

控制器先按当前世界、加载区域、账号权限、隐私规则和本地策略过滤、评分，最多提交 12 个候选。
`Candidate(targetId, kind, score, label)` 的评分为 0～100 整数；
`kind` 仅 `PLAYER`、`VIEWPOINT`、`AREA`。当前控制器为场景评分 80、玩家评分 20，
这只是场景优先的本地规则，尚不代表建筑密度或热力分析。builder 保留调用方的候选顺序。

`build(candidates, missingSlots, queuedTargetIds)` 生成 `PreparedDecision`。缺口范围为 0～12，
已排目标被排除，剩余候选依次获得 `c1`、`c2` 等临时 ID。
动态 user JSON 只有缺口与小表，每行依次为短 ID、类型、评分、短名称：

```json
{"missing":2,"candidates":[["c1","VIEWPOINT",95,"车站"],["c2","AREA",81,"城区"],["c3","PLAYER",74,"player"]]}
```

模型只负责补充待播条目，不能重排或删除人工队列。固定规则要求优先高分、同分按候选顺序，
只输出 JSON，例如 `{"picks":["c1","c2"]}`，不输出解释、思维链、命令或低层动作。
可以少选或返回 `{"picks":[]}`。无候选或缺口为零时不请求模型。

玩家名称固定替换为 `player`，场景名称最多保留 24 个 Unicode 码点，名称只是 JSON 数据。
真实 targetId/UUID 只保留在该请求的本地映射中，不进入模型输入或前端预览；
输入也不包含坐标、轨迹、聊天、黑名单或凭据，不应把这些内容放进场景短名称。

## 输出与执行检查

`PreparedDecision.parseResponse(text)` 返回真实 targetId 的不可变列表，保留模型给出的顺序。
最多接受 1024 UTF-8 字节，只接受包含唯一 `picks` 字段的 JSON 对象。以下错误整批拒绝：

- 未知、重复或超出请求缺口的短 ID；
- 额外字段、重复字段、动作、Markdown、解释、尾随数据或宽松 JSON；
- 非字符串条目、截断、缺少 `picks`、非法结构或过大响应。

只使用产生该请求的 `PreparedDecision` 解释响应，短 ID 不跨请求复用。
HTTP 层还要求正常结束的单条最终 `content`；截断、空内容、已报告的推理 token 或返回思考内容不能执行。

回包后在游戏线程重新检查手动运行意图、配置版本、提供方、世界和运行代次。
停止、切世界、断线、修改配置或队列后失效的结果被丢弃，不能用迟到响应重新开播。
仍有效的建议按最新目标状态过滤，再按当前缺口、重复目标与队列上限补到队尾；
失效候选不会执行，也不会重排人工条目。玩家条目执行时仍须经过服务端授权、15 秒预告与拒绝机制。

## 请求调度与回退

只有显式启用、完整配置、导播运行、未暂停、自动补足开启且有合法候选缺口时才发起策略请求。
默认待播数为 4，可由队列配置调整为 1～12；模型不能自行扩大数量。
当前镜头继续运行，AI 请求只等待队尾补充，不阻塞游戏主线程。

两次策略请求尝试至少间隔 60 秒，从开始尝试时计时，失败或超时也计入，不能立即重试。
超时、连接失败、提供方拒绝、思考模式不兼容、截断或非法选择均回到本地默认补足；
间隔未到时也使用本地策略。手动停止始终优先，不因请求完成而恢复导播。

HTTP 连接超时为 5 秒，单请求超时为 15 秒，不自动跟随重定向或重试。
Chat 响应体上限 64 KiB，最终选择仍须通过上述更小的 1024 字节严格解析限制。

## 当前非思考请求

实际请求使用 `POST <baseUrl>/chat/completions`、`stream:false`、
`response_format:{"type":"json_object"}`，只携带固定 system 与当前 user 小表，
不累积聊天历史，不发送工具定义、键鼠动作或游戏图像。

DeepSeek 显式发送 `thinking:{"type":"disabled"}` 和 `max_tokens:256`。
示例形状如下；模型 ID 使用账号实际可用且兼容非思考模式的值，不自动改写用户配置：

```json
{
  "model": "<账号可用的模型ID>",
  "thinking": {"type": "disabled"},
  "response_format": {"type": "json_object"},
  "max_tokens": 256,
  "stream": false,
  "messages": [
    {"role": "system", "content": "<StrategyPrompt.SYSTEM_PROMPT 原文>"},
    {"role": "user", "content": "{\"missing\":2,\"candidates\":[[\"c1\",\"VIEWPOINT\",95,\"车站\"],[\"c2\",\"AREA\",81,\"城区\"]]}"}
  ]
}
```

OpenAI 使用 `max_completion_tokens:256`。当前客户端对 `gpt-4*` / `chatgpt-4o*` 名称不附加推理参数，
其他名称显式发送 `reasoning_effort:"none"`；模型不接受此参数或不支持非思考时失败并回退本地，
不会静默改为付费思考模式继续请求。名称规则与参数兼容仍需以实际模型响应为准。

256 token 是输出预算上限，不是总 token、费用或响应延迟保证。
JSON 模式只约束格式，不能证明短 ID 合法；固定提示词包含 JSON 要求与样例，
仍需处理空输出并执行本地解析和最终检查。
参数依据见 [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、
[DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode/) 与
[OpenAI API 官方参考](https://developers.openai.com/api/reference/overview)。

## 连接测试、密钥与会话用量

WebUI 的 `aiTest` 只对当前已保存的提供方请求 `GET <baseUrl>/models`。
地址和密钥需已保存，模型 ID 可暂留空；返回的模型列表可选入配置草稿，再保存并显式启用策略。
测试不发 Chat 请求、不启用策略、不计入策略 token 统计；模型列表可读不证明 Chat 权限、参数兼容或非思考能力。
配置改变后清空旧列表并丢弃旧测试结果，避免把另一份配置的测试误认为当前结果。

密钥须为不含空白的 ASCII 字符串，不应包含字段名、引号或说明文字。
密钥只在客户端本机配置与提供方请求层使用，不发给游戏服务器，不进入提示词、预览、日志或浏览器持久化；
状态只返回是否存在。保存成功不等于通过网络、鉴权或真实付费 API 验证。

`ai.usage` 记录本次客户端进程会话的 `requests`、`promptTokens`、`completionTokens`、`totalTokens` 和
`unreportedRequests`。策略开始尝试即增加请求数；提供方返回合法 usage 时累加，
即使该响应因停止或上下文改变而不能执行，已发生的用量仍计入。
失败或未返回合法 usage 的请求单独计入 `unreportedRequests`，不能据此认定零 token 或零费用。
此统计不跨客户端重启保存，不是提供方账单，也不自动换算人民币。
