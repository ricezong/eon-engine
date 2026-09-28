package cn.kong.eon.engine.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.kong.eon.engine.EngineConfig;
import cn.kong.eon.engine.msg.ChatMsg;
import cn.kong.eon.engine.msg.ToolCall;
import cn.kong.eon.engine.port.model.LlmPort;
import cn.kong.eon.engine.port.store.LedgerPort;
import cn.kong.eon.engine.port.store.LedgerPort.LedgerRecord;
import cn.kong.eon.engine.window.Block;
import cn.kong.eon.engine.window.BlockKind;
import cn.kong.eon.engine.window.ContextWindow;

/**
 * 压缩工具箱：对窗口的全部加工都在这里，窗口只提供改写原语。
 * 就地降档（{@link #snip} / {@link #prune} / {@link #clearOldThinking}）均幂等；
 * {@link #summarize} 生成 SUMMARIZE 档摘要。降档失败即零变更——宁不压缩也不丢真相。
 */
public final class Compressor {

    private static final Logger log = LoggerFactory.getLogger(Compressor.class);

    /** 超过该长度的字符串字段才被视为大字段并替换。 */
    private static final int LONG_FIELD_CHARS = 200;

    /** 必须是固定字面量：带变量会破坏幂等。 */
    private static final String TRUNCATE_SEP = "\n...\n";

    private static final String REF_HINT = ResultIntake.REF_HINT;
    private static final String CLEARED_PREFIX = "[旧工具结果内容已清除，" + REF_HINT;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmPort llm;
    private final LedgerPort ledger;
    private final int maxInputChars;
    private final int maxOutputChars;

    public Compressor(LlmPort llm, LedgerPort ledger, EngineConfig config) {
        this.llm = llm;
        this.ledger = ledger;
        this.maxInputChars = config.summarizeMaxInputChars();
        this.maxOutputChars = config.summarizeMaxOutputChars();
    }

    // ---- 就地降档 ----

    /** SNIP：保护区外的 TOOL_RESULT 头尾截断。 */
    public void snip(ContextWindow window, int keepPerSide) {
        int from = window.protectedFrom();
        for (int i = 0; i < from; i++) {
            Block b = window.blockAt(i);
            if (b.kind() != BlockKind.TOOL_RESULT) {
                continue;
            }
            String cut = headTail(b.text(), keepPerSide);
            window.replace(i, b.withText(cut));
        }
    }

    /** PRUNE：保护区外 AI_TEXT 截断、TOOL_ARGS 骨架化、TOOL_RESULT 换占位符或截断。已覆盖 SNIP 的全部动作。 */
    public void prune(ContextWindow window, int keepPerSide, int argsMinChars) {
        int from = window.protectedFrom();
        for (int i = 0; i < from; i++) {
            Block b = window.blockAt(i);
            switch (b.kind()) {
                case ASSISTANT -> {
                    Block next = b;
                    String cut = headTail(b.text(), keepPerSide);
                    if (!Objects.equals(cut, b.text())) {
                        next = next.withText(cut);
                    }
                    List<ToolCall> slim = skeletonize(b.toolCalls(), argsMinChars);
                    if (slim != b.toolCalls()) {
                        next = next.withToolCalls(slim);
                    }
                    if (next != b) {
                        window.replace(i, next);
                    }
                }
                case TOOL_RESULT -> {
                    String ref = tailRef(b.text());
                    String cut = ref != null
                            ? CLEARED_PREFIX + ref + "]"
                            : headTail(b.text(), keepPerSide);
                    if (!Objects.equals(cut, b.text())) {
                        window.replace(i, b.withText(cut));
                    }
                }
                case USER -> { /* USER_INPUT 不参与 SNIP/PRUNE */ }
            }
        }
    }

    /** 思维链清除：只保留最近 keepTurns 段，更早的置 null。不受档位与保护区约束。 */
    public void clearOldThinking(ContextWindow window, int keepTurns) {
        int assistants = 0;
        for (int i = 0; i < window.size(); i++) {
            if (window.blockAt(i).kind() == BlockKind.ASSISTANT) {
                assistants++;
            }
        }
        int toClear = assistants - Math.max(0, keepTurns);
        for (int i = 0, seen = 0; seen < toClear && i < window.size(); i++) {
            Block b = window.blockAt(i);
            if (b.kind() != BlockKind.ASSISTANT) {
                continue;
            }
            seen++;
            if (b.thinking() != null) {
                window.replace(i, b.withThinking(null));
            }
        }
    }

    /** 头尾各留 keepPerSide；不超过两倍时原样返回。 */
    private static String headTail(String s, int keepPerSide) {
        if (s == null || s.length() <= keepPerSide * 2) {
            return s;
        }
        return s.substring(0, keepPerSide) + TRUNCATE_SEP + s.substring(s.length() - keepPerSide);
    }

    /** 提取已落盘结果的 refId。只认「以 ] 结尾且含固定标记」，避免正文自带该字面量的未落盘结果被误判。 */
    private static String tailRef(String text) {
        if (text == null || !text.endsWith("]")) {
            return null;
        }
        int i = text.lastIndexOf(REF_HINT);
        if (i < 0) {
            return null;
        }
        String ref = text.substring(i + REF_HINT.length(), text.length() - 1);
        return ref.isEmpty() || ref.indexOf('\n') >= 0 ? null : ref;
    }

    /** 长参数骨架化；仅当结果严格更短时采纳，无变化时原样返回同一实例。 */
    private List<ToolCall> skeletonize(List<ToolCall> calls, int argsMinChars) {
        if (calls.isEmpty()) {
            return calls;
        }
        List<ToolCall> slim = null;
        for (int i = 0; i < calls.size(); i++) {
            ToolCall c = calls.get(i);
            String args = c.argumentsJson();
            if (args == null || args.length() <= argsMinChars) {
                continue;
            }
            String skeleton = skeletonArgs(args);
            if (skeleton != null && skeleton.length() < args.length()) {
                if (slim == null) {
                    slim = new ArrayList<>(calls);
                }
                slim.set(i, new ToolCall(c.id(), c.name(), skeleton));
            }
        }
        return slim == null ? calls : List.copyOf(slim);
    }

    // ---- 摘要 ----

    /**
     * 生成/合并摘要。原料取自账本 {@code seq ∈ [fromSeq, toSeq]}——窗口可能已被 SNIP/PRUNE 降级，
     * 账本才是全文。部分段失败则跳过继续合并；全部段失败返回指向账本位置的兜底摘要。
     *
     * @return null 当且仅当原料为空，调用方须零变更
     */
    public String summarize(String sessionId, long fromSeq, long toSeq, String existingSummary) {
        List<LedgerRecord> material = ledger.replay(sessionId, fromSeq).stream()
                .filter(r -> r.seq() <= toSeq)
                .toList();
        if (material.isEmpty()) {
            return null;
        }
        String ledgerLocator = ledger.locator(sessionId);
        List<String> segments = segment(material);
        String summary = existingSummary;
        boolean anySuccess = false;

        for (int i = 0; i < segments.size(); i++) {
            String merged = generateSummary(segments.get(i), summary, i + 1, segments.size(), ledgerLocator);
            if (merged == null || merged.isBlank()) {
                log.warn("[Compress] 第 {}/{} 段摘要失败，跳过", i + 1, segments.size());
                continue;
            }
            summary = merged;
            anySuccess = true;
        }

        if (!anySuccess) {
            log.warn("[Compress] 全部 {} 段摘要均失败，使用兜底摘要", segments.size());
            return fallback(ledgerLocator);
        }
        return summary;
    }

    /** 参数骨架化：过长的字符串字段替换为一行说明，结构与其余字段保留。null = 不适用，调用方保留原参数。 */
    public String skeletonArgs(String argumentsJson) {
        Map<String, Object> args = parse(argumentsJson);
        if (args.isEmpty()) {
            return null;
        }
        Map<String, Object> slim = new LinkedHashMap<>();
        boolean replacedAny = false;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.length() > LONG_FIELD_CHARS) {
                slim.put(entry.getKey(), "<" + s.length() + " 字符已裁剪，原文不在上下文中>");
                replacedAny = true;
            } else {
                slim.put(entry.getKey(), value);
            }
        }
        if (!replacedAny) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(slim);
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON → 有序 Map；解析失败返回空 Map。 */
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String fallback(String ledgerLocator) {
        return """
                [摘要生成失败] 历史对话的自动摘要不可用，以下为原始记录要点提示。
                完整对话记录位置: %s
                请在需要时按该位置查阅原始记录。""".formatted(ledgerLocator != null ? ledgerLocator : "(不可用)");
    }

    /** 账本记录序列化为带标签的行，按 maxInputChars 分段（段边界永不切断一行）。思维链不进摘要原料。 */
    private List<String> segment(List<LedgerRecord> records) {
        List<String> lines = new ArrayList<>();
        for (LedgerRecord r : records) {
            ChatMsg m = r.msg();
            switch (m.role()) {
                case USER -> lines.add("[USER] " + m.text());
                case ASSISTANT -> {
                    if (m.text() != null && !m.text().isBlank()) {
                        lines.add("[AI] " + m.text());
                    }
                    for (ToolCall c : m.toolCalls()) {
                        lines.add("[TOOL:" + c.name() + "] " + (c.argumentsJson() == null ? "" : c.argumentsJson()));
                    }
                }
                case TOOL -> lines.add("[RESULT:" + m.toolName() + "] " + m.text());
                default -> { /* SYSTEM 不进摘要原料 */ }
            }
        }
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (current.length() + line.length() + 1 > maxInputChars && current.length() > 0) {
                segments.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments;
    }

    private String generateSummary(String segmentText, String existingSummary,
                                   int index, int total, String ledgerLocator) {
        String existingSection = existingSummary == null || existingSummary.isBlank()
                ? "（无——这是首次摘要）"
                : existingSummary;
        String segmentHint = total > 1
                ? "当前是第 " + index + "/" + total + " 段，请与旧摘要合并为一份结构相同的新摘要。"
                : "";

        String prompt = """
                请将以下对话片段合并进会话摘要。摘要结构固定为四段：
                1. History Q&A（历史问答列表）
                2. Key Context and Decisions
                3. User Preferences and Updates
                4. Pending Tasks and Current Work

                摘要要求：
                - 输出不超过 %d 字符。
                - 第 1 段每条格式：
                  - [已完成] <user_input>用户消息原文</user_input>
                    - 结论: ...
                    - 关键事实: ...
                  - [进行中] <user_input>用户消息原文</user_input>
                    - 结论: ...
                    - 关键事实: ...
                %s

                关于第 1 段
                - 每轮问答占一个条目，**历史问答与最新一次问答一视同仁**，都要逐条列出。
                - 用户消息原文会从上下文删除，这里是后续了解用户诉求的唯一依据：逐条照抄全文，
                  不得概括、压缩或合并。片段中以 [USER] 开头的内容即为用户消息，去掉 [USER] 前缀即为原文，
                  用 <user_input> 标签包裹。已完结的标 [已完成]，尚未做完的标 [进行中]。
                - 每次提问至少留一个条目，绝不因为两次提问看起来相似就合并成一条。
                - 旧摘要里已存在的条目**原样保留、只增不删**，不得删改、合并或重写。
                - 关键事实优先记产出物与文件路径——这些内容一旦丢失就无法从对话里再找回。

                关于第 2~4 段
                - **只覆盖最新一次问答**（片段中最后那次用户提问），不要把历史问答的决策和待办堆进来。
                - 用陈述句，保留关键事实、文件路径与数字，省略过程性描述。
                - 若旧摘要的第 2~4 段描述的是更早的提问，而本片段出现了更新的提问：
                  把旧的第 2~4 段内容压缩成结论与关键事实，随那次提问并入第 1 段作为历史条目，
                  第 2~4 段改写为本片段最后那次问答的情况。

                对话片段格式说明：
                - [USER] 开头的为用户消息
                - [AI] 开头的为助手回复
                - [TOOL:工具名] 开头的为工具调用参数
                - [RESULT:工具名] 开头的为工具执行结果

                容量不够时
                - 逼近字符上限时，从**最早**的历史条目开始降级：保留 <user_input> 原文，只留诉求要点一句话 + 关键事实。
                  最新一次问答与第 2~4 段始终完整。

                其他：
                - 片段不构成一次完整问答（如只剩工具调用）时，把信息并入第 2 段 Key Context and Decisions，
                  不要单建条目。

                输出要求：不超过 %d 字符；只输出上述结构，不要任何额外说明。
                %s
                完整对话记录位置: %s

                === 旧摘要 ===
                %s

                === 待摘要的对话片段 ===
                %s
                """.formatted(maxOutputChars, segmentHint,
                maxOutputChars, segmentHint,
                ledgerLocator != null ? ledgerLocator : "(账本位置不可用)",
                existingSection, segmentText);

        try {
            String summary = llm.complete(List.of(
                    ChatMsg.system("你是一个对话摘要生成器。请严格按指令生成摘要。"),
                    ChatMsg.user(prompt)));
            return (summary != null && !summary.isBlank()) ? summary : null;
        } catch (Exception e) {
            log.error("[Compress] LLM 不可用，第 {}/{} 段跳过: {}", index, total, e.getMessage());
            return null;
        }
    }
}
