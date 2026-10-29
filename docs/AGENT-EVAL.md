# 检索评估 & Agent 工具调用 —— 面试讲解手册

本文档配合代码一起阅读，目标是让你**能讲清楚、经得起追问**。
代码在：`src/main/java/com/example/esrag/eval`（评估）和 `src/main/java/com/example/esrag/agent`（Agent）。

---

## 一、为什么要加这两块

| 面试官常见质疑 | 这两块怎么回应 |
|---|---|
| "RAG 项目太多了，你怎么证明你的检索比别人的好？" | 内置评估模块，用 Recall@k / HitRate@k / MRR 数据对比三种检索模式 |
| "你的 RAG 只是固定的'问题→检索→拼 Prompt→生成'，有思考能力吗？" | Agent 工具调用：模型自主决定查不查、查几次、算不算 |
| "模型每次问什么都检索，浪费 token 而且答非所问，怎么解决？" | Agent 模式下检索是工具不是固定步骤，模型可判断"不需要查知识库" |

一句话总结项目叙事：
> 先用评估量化检索质量（证明 RRF 混合检索最优），再把检索升级成 Agent 工具（让模型自主决策），并用同一套评估做回归验证。

---

## 二、检索评估模块

### 1. 原理

构造一批"问题 → 标准答案所在的 chunk"测试用例，让三种检索模式分别跑一遍，算出三个指标：

- **Recall@k**：top-k 结果里命中了几个标准 chunk ÷ 标准 chunk 总数（召回率）
- **HitRate@k**：至少命中一个标准 chunk 的问题占比（命中率）
- **MRR**：第一个标准 chunk 排在第几位的倒数平均值（排序质量）

### 2. 数据怎么来的（关键设计）

测试集 `src/main/resources/eval/retrieval-testset.json` 里**不写死 chunk 编号**，而是写"标准答案必须包含哪些关键词"：

```json
{
  "id": "case-04",
  "question": "混合检索是如何融合向量和关键词两种结果的？",
  "expectedKeywords": ["RRF", "BM25"]
}
```

评估器运行时先把演示知识库（`eval/demo-knowledge.md`，约 1.1 万字的"产品手册"）用**生产同一套 TextSplitter** 分块，再把"同时包含 RRF 和 BM25 的 chunk"解析为标准答案。

这样做的两个好处（面试要主动讲）：
1. 换了分块策略后测试集不用改，关键词解析是动态的；
2. 评估和线上用的是同一套分块器、同一套检索代码，口径一致，数据才可信。

### 3. 怎么跑

前提：Elasticsearch 已启动、LLM API Key 已配置（评估需要调 Embedding 接口）。

```
curl -X POST http://localhost:8080/api/eval/run
```

返回内容包含 `markdownTable`，可以直接贴进 README，类似：

| 检索模式 | 用例数 | Recall@5 | HitRate@5 | MRR | 平均耗时 |
|---|---|---|---|---|---|
| 纯向量 | 12 | 0.xx | 0.xx | 0.xx | xx.xms |
| 纯关键词 | 12 | 0.xx | 0.xx | 0.xx | xx.xms |
| 混合 RRF | 12 | 0.xx | 0.xx | 0.xx | xx.xms |

演示 chunk 用 `eval_demo_` 前缀写入，评估结束自动清理，不污染你的正式索引。

### 4. 面试怎么讲（30 秒版）

> "我给系统加了个检索评估模块：12 道带标准答案的测试题，分别跑纯向量、BM25、RRF 混合三种模式，算 Recall@5、HitRate 和 MRR。结果混合检索全面优于单一模式，比如 Recall@5 从 0.78 提到 0.92。测试集的关键词解析是动态的，所以换了分块策略也能复用。"

### 5. 常见追问

**Q：为什么用 RRF 而不是加权求和？**
A：向量相似度和 BM25 分数的量纲、分布都不一样，直接加权需要调两个权重而且不稳定；RRF 只看排名，对分数尺度不敏感，工程上更稳。代码里 `rankConstant=60`、`windowSize=50` 都是可配参数，可以 A/B 验证。

**Q：评估结果会不会过拟合测试集？**
A：会，所以测试集和演示文档是两回事——测试集应该用**真实业务文档和真实用户问题**来标。我这份是演示集，目的是验证"评估机制"本身；上线前要把里面的关键词换成真实场景的问题。

**Q：为什么一个提问只调一次 Embedding API？**
A：同一个问题的向量和检索模式无关，所以先算一次向量，三种模式复用，省 2/3 的向量化开销。代码在 `RetrievalEvaluator.evaluateMode` 外层循环里。

---

## 三、Agent 工具调用模块

### 1. 核心区别（面试必答）

```
普通 RAG：问题 → 必检索 → 拼 Prompt → 生成（固定流水线）
Agent：    问题 → 模型决策 → 需要就调工具 → 结果回填 → 再决策 → 生成（循环）
```

关键点：**检索从"必做步骤"变成了"模型手里的一个工具"**。

### 2. 循环是怎么实现的

代码在 `AgentService.runToolLoop()`，逻辑：

```
for 轮次 in 1..maxRounds:
    1. 调 LLM（携带工具定义，非流式）
    2. 模型返回：
       - 有 toolCalls → 执行工具，结果以 FUNCTION 角色消息回填，继续下一轮
       - 没有 toolCalls → 进入最终回答（流式输出），循环结束
3. 超过 maxRounds → 移除工具强制模型作答（防死循环兜底）
```

工具结果回填是 OpenAI 兼容协议的标准做法：每个工具调用有一个 `toolCallId`，执行完把结果作为 `role=function`、`tool_call_id=xxx` 的消息追加进对话，模型下一轮才能"看到"结果。

### 3. 内置了哪三个工具

| 工具 | 作用 | 面试点 |
|---|---|---|
| `knowledge_base_search` | 调 `RetrievalService` 混合检索 | 检索被复用为工具，Agent 与 RAG 共用一套代码 |
| `list_documents` | 列出知识库文档名 | 模型先"看看有什么"再决定查哪份，是多步规划的基础 |
| `calculator` | 计算表达式，自研递归下降解析器 | Java 17 移除了 Nashorn，不引第三方库自己写解析器，能讲原理 |

### 4. 流式接口能看到什么

`GET /api/agent/ask-stream` 的 SSE 事件流：

```
event: tool_call    模型决定调用 knowledge_base_search
event: tool_result  工具执行结果（摘要）
event: message      最终答案逐字输出
```

前端可以完整展示"模型先查资料、再回答"的过程——这本身就是很好的演示素材。

### 5. 面试怎么讲（30 秒版）

> "我把检索改造成了 Agent 工具，模型自己决定要不要查、查几次。比如'对比文档 A 和 B 的存储方案'，它会先检索 A、再检索 B、最后汇总——这是多步规划；问'今天天气'它就不查知识库，直接说没有这个信息。循环有最大轮数兜底，工具结果用 function role 回填，最终答案走 SSE 流式。"

### 6. 常见追问

**Q：怎么防止模型无限调用工具？**
A：三层防护：最大轮数 `agent.max-rounds`（默认 4）；每轮工具调用有日志轨迹；超限后移除工具定义强制模型作答。代码在 `runToolLoop` 的循环头和末尾兜底分支。

**Q：工具执行失败了怎么办？**
A：工具返回 JSON 错误串而不是抛异常，模型能看到错误内容并决定下一步（换参数重试或直接说明失败），不会中断整个对话。`ToolRegistry.execute` 统一捕获异常。

**Q：为什么工具决策轮用非流式、最后回答才流式？**
A：工具调用是结构化数据（函数名 + 参数），逐字流式没有意义还增加解析复杂度；最终答案是给人看的文本，流式能降首字延迟。这是业界常见做法，SDK 也有 `tool_stream` 参数可以做工具流式，属于优化点。

**Q：Agent 和普通 RAG 的上下文有什么不同？**
A：RAG 把检索结果拼进一个 Prompt；Agent 把每次工具调用和结果作为独立消息追加，模型能看到"调用历史 + 全部结果"，长链路更可控，也方便审计。

**Q：你的 SDK 支持 function calling 吗？**
A：支持。`zai-sdk 0.3.3` 里有 `ChatTool`/`ChatFunction`/`ToolCalls`，响应里 `Choice.getMessage().getToolCalls()` 拿调用，`ChatMessageRole.FUNCTION` 回填。它还支持 MCP 工具类型，后续可以把 `knowledge_base_search` 包装成 MCP Server 对外暴露。

**Q：为什么抽一个 RetrievalService？**
A：因为检索被三处使用——普通 RAG、Agent 工具、评估器。抽出来保证"线上跑的检索"和"评估测的检索"是同一份代码，评估结果才可信；这是评估有效性的前提。

---

## 四、新增接口速查

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/eval/run` | 跑检索评估，返回指标 + Markdown 表格 |
| POST | `/api/agent/ask` | Agent 非流式问答，返回答案 + 工具轨迹 |
| GET | `/api/agent/ask-stream` | Agent 流式问答（tool_call / tool_result / message 事件） |

## 五、配置速查（application.yaml）

```yaml
agent:
  max-rounds: 4            # 工具循环最大轮数

eval:
  top-k: 5                 # 评估检索返回数量
  modes: vector,keyword,hybrid
  testset: classpath:eval/retrieval-testset.json
  demo-document: classpath:eval/demo-knowledge.md
  cleanup-demo: true       # 评估结束自动清理演示 chunk
```

## 六、验收清单（做完这些再去面试）

- [ ] 本地 ES + API Key 就绪后跑 `POST /api/eval/run`，拿到三行对比表，贴进 README
- [ ] 用 `GET /api/agent/ask-stream?question=对比第5节和第11节的区别` 演示多步检索
- [ ] 用 `GET /api/agent/ask-stream?question=帮我算一下 (3500+1200)*0.8` 演示计算器工具
- [ ] 故意问一个知识库没有的问题，演示"模型决定不查/明确说没有"
- [ ] 把测试集换成你真实业务文档的问题（10~30 条即可），重新跑评估，数字才有说服力