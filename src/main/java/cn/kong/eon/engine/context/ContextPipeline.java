package cn.kong.eon.engine.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.kong.eon.engine.EngineConfig;
import cn.kong.eon.engine.TokenEstimator;
import cn.kong.eon.engine.event.EngineEvent;
import cn.kong.eon.engine.event.EventPublisher;
import cn.kong.eon.engine.msg.ChatMsg;
import cn.kong.eon.engine.msg.Role;
import cn.kong.eon.engine.msg.ToolCall;
import cn.kong.eon.engine.msg.ToolSpec;
import cn.kong.eon.engine.scope.Session;
import cn.kong.eon.engine.window.Block;
import cn.kong.eon.engine.window.ContextWindow;

/**
 * 上下文管线：思维链清除 → 估量判档 → 就地压缩 → 复测水位 → 组装 → 配对修复。
 *
 * <p>压缩破坏性改写窗口，账本 append-only 为真相源。判档与组装同口径（measure），任何路径恒定组装一次。
 *
 * <p>布局：System → sections[order&lt;1000] → &lt;session_summary&gt; → 窗口历史 → sections[order≥1000] → nudges。
 */
public final class ContextPipeline {

    private static final Logger log = LoggerFactory.getLogger(ContextPipeline.class);

    private final EngineConfig config;
    private final TokenEstimator estimator;
    private final Compressor compressor;
    private final List<ContextSection> headSections;
    private final List<ContextSection> tailSections;
    private final WaterLevels levels;
    private final EventPublisher events;

    public ContextPipeline(EngineConfig config,
                           TokenEstimator estimator,
                           List<ContextSection> sections,
                           Compressor compressor,
                           EventPublisher events) {
        this.config = config;
        this.estimator = estimator;
        this.compressor = compressor;
        List<ContextSection> sorted = sections == null
                ? List.of()
                : sections.stream().sorted(Comparator.comparingInt(ContextSection::order)).toList();
        this.headSections = sorted.stream().filter(s -> s.order() < 1000).toList();
        this.tailSections = sorted.stream().filter(s -> s.order() >= 1000).toList();
        this.levels = new WaterLevels(config.snipWaterLevel(), config.pruneWaterLevel(), config.summarizeWaterLevel());
        this.events = events;
    }

    public List<ChatMsg> assemble(Session session, List<String> nudges, List<ToolSpec> specs) {
        ContextWindow window = session.window();
        compressor.clearOldThinking(window, config.thinkingReplayTurns());
        if (config.maxContextTokens() <= 0) {
            return repairPairing(layout(session, nudges));
        }
        long before = measure(session, nudges, specs);
        CompressionLevel level = levels.judge((double) before / config.maxContextTokens());
        if (level != CompressionLevel.NONE) {
            log.debug("[Compression] {} 档触发 session={}", level, session.id());
            compress(session, level);
            long after = measure(session, nudges, specs);
            events.publish(new EngineEvent.CompressionApplied(session.id(), level, before, after));
        }
        return repairPairing(layout(session, nudges));
    }

    private void compress(Session session, CompressionLevel level) {
        ContextWindow window = session.window();
        switch (level) {
            case SNIP -> compressor.snip(window, config.snipKeepChars());
            case PRUNE -> compressor.prune(window, config.snipKeepChars(), config.argsPruneMinChars());
            case SUMMARIZE -> summarize(session);
            case NONE -> { }
        }
    }

    // ---- 布局 ----

    List<ChatMsg> layout(Session session, List<String> nudges) {
        List<ChatMsg> msgs = new ArrayList<>();
        if (notBlank(config.systemPrompt())) {
            msgs.add(ChatMsg.system(config.systemPrompt()));
        }
        for (ContextSection section : headSections) {
            addSection(msgs, session.id(), section);
        }
        String summary = session.state().compression().lastSummary();
        if (notBlank(summary)) {
            msgs.add(ChatMsg.user(wrap("summary", summary)));
        }
        for (Block b : session.window().blocks()) {
            msgs.add(toMsg(b));
        }
        for (ContextSection section : tailSections) {
            addSection(msgs, session.id(), section);
        }
        if (nudges != null && !nudges.isEmpty()) {
            msgs.add(ChatMsg.user(String.join("\n", nudges)));
        }
        return msgs;
    }

    private static ChatMsg toMsg(Block b) {
        return switch (b.kind()) {
            case USER -> ChatMsg.user(b.text());
            case ASSISTANT -> ChatMsg.assistant(b.text(), b.thinking(), b.toolCalls());
            case TOOL_RESULT -> ChatMsg.toolResult(b.toolCallId(), b.toolName(), b.text(), b.toolView());
        };
    }

    /** 渲染失败按本轮缺省处理，不阻断循环。 */
    private void addSection(List<ChatMsg> msgs, String sessionId, ContextSection section) {
        String text;
        try {
            text = section.render(sessionId);
        } catch (Exception e) {
            log.warn("[Context] 注入段渲染失败 session={}, section={}", sessionId, section.label(), e);
            return;
        }
        if (notBlank(text)) {
            msgs.add(ChatMsg.user(wrap(section.label(), text)));
        }
    }

    // ---- 估量与判档 ----

    /** 判档依据 = 组装口径用量（与最终发送一致，含注入段、摘要与工具表）；不构建消息列表。
     * 必须在 clearOldThinking 之后调用。 */
    long measure(Session session, List<String> nudges, List<ToolSpec> specs) {
        long total = 0;
        if (notBlank(config.systemPrompt())) {
            total += estimator.estimate(config.systemPrompt());
        }
        for (ContextSection section : headSections) {
            total += estimateSection(session.id(), section);
        }
        String summary = session.state().compression().lastSummary();
        if (notBlank(summary)) {
            total += estimator.estimate(wrap("summary", summary));
        }
        for (Block b : session.window().blocks()) {
            total += estimator.estimate(blockText(b));
        }
        for (ContextSection section : tailSections) {
            total += estimateSection(session.id(), section);
        }
        if (nudges != null && !nudges.isEmpty()) {
            total += estimator.estimate(String.join("\n", nudges));
        }
        return total + toolOverhead(specs);
    }

    /** 渲染失败按本轮缺省处理，与 addSection 口径一致。 */
    private long estimateSection(String sessionId, ContextSection section) {
        String text;
        try {
            text = section.render(sessionId);
        } catch (Exception e) {
            return 0;
        }
        return notBlank(text) ? estimator.estimate(wrap(section.label(), text)) : 0;
    }

    /** 工具表发送开销：名称+描述+schema 合计，每次组装计算一次，口径与消息估算同源。 */
    private long toolOverhead(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (ToolSpec s : specs) {
            sb.append(s.name()).append(s.description());
            if (s.parametersJsonSchema() != null) {
                sb.append(s.parametersJsonSchema());
            }
        }
        return estimator.estimate(sb.toString());
    }

    /** 与单消息估算同口径：text + thinking + toolCalls(name + argumentsJson) 直拼。 */
    private static String blockText(Block b) {
        StringBuilder sb = new StringBuilder();
        if (b.text() != null) {
            sb.append(b.text());
        }
        if (b.thinking() != null) {
            sb.append(b.thinking());
        }
        for (ToolCall c : b.toolCalls()) {
            sb.append(c.name()).append(c.argumentsJson() == null ? "" : c.argumentsJson());
        }
        return sb.toString();
    }

    // ---- SUMMARIZE ----

    /**
     * 摘要 → 回收窗口 → 推进水位，三步之间不得插入外部代码。
     * LLM 不可用时用兜底摘要照常回收；只有「无可摘要区间」与「原料为空」是零变更。
     */
    private void summarize(Session session) {
        ContextWindow window = session.window();
        List<Block> blocks = window.blocks();
        int guard = config.tailGuardBlocks();
        if (blocks.size() <= guard) {
            return; // 无可摘要区间
        }
        long guardStartSeq = blocks.get(blocks.size() - guard).messageSeq();
        CompressionState state = session.state().compression();
        String summary = compressor.summarize(session.id(), blocks.get(0).messageSeq(), guardStartSeq - 1,
                state.hasSummary() ? state.lastSummary() : null);
        if (summary == null || summary.isBlank()) {
            log.info("[Compression] SUMMARIZE 无原料，零变更 session={}", session.id());
            return;
        }
        long freed = window.removeBeforeSeq(guardStartSeq);
        session.state().setCompression(new CompressionState(
                summary, guardStartSeq - 1, state.summarizeCount() + 1));
        log.info("[Compression] SUMMARIZE 完成 session={}, freed≈{} tokens, 水位推进至 seq={}",
                session.id(), freed, guardStartSeq - 1);
    }

    // ---- 配对修复 ----

    /**
     * 配对修复：有调用无结果 → 补合成结果；有结果无调用 → 丢弃。
     * 只作用于消息列表，不改窗口、不落账本。
     *
     * <p>悬空调用来自账本写入顺序：ASSISTANT 落盘后、结果落盘前的任何中断都会留下它。
     */
    private static List<ChatMsg> repairPairing(List<ChatMsg> msgs) {
        Set<String> called = new HashSet<>();
        for (ChatMsg m : msgs) {
            for (ToolCall c : m.toolCalls()) {
                called.add(c.id());
            }
        }
        Set<String> answered = new HashSet<>();
        for (ChatMsg m : msgs) {
            if (m.role() == Role.TOOL) {
                answered.add(m.toolCallId());
            }
        }
        List<ChatMsg> out = new ArrayList<>(msgs.size());
        for (ChatMsg m : msgs) {
            if (m.role() == Role.TOOL && !called.contains(m.toolCallId())) {
                continue; // 孤儿结果丢弃
            }
            out.add(m);
            if (m.role() == Role.ASSISTANT) {
                for (ToolCall c : m.toolCalls()) {
                    if (!answered.contains(c.id())) {
                        out.add(ChatMsg.toolResult(c.id(), c.name(), "[工具执行结果缺失]", null));
                    }
                }
            }
        }
        return out;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 用 XML 标签包裹内容。 */
    private static String wrap(String label, String content) {
        return "<" + label + ">\n" + content + "\n</" + label + ">";
    }
}
