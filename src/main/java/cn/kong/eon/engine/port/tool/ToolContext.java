package cn.kong.eon.engine.port.tool;

import cn.kong.eon.engine.event.EventPublisher;
import cn.kong.eon.engine.port.interaction.InteractionChannel;
import cn.kong.eon.engine.port.store.ToolResultPort;
import cn.kong.eon.engine.port.store.Workspace;

/** 工具执行的会话级上下文，引擎在每次工具执行时注入。 */
public record ToolContext(
        String sessionId,
        int turn,
        Workspace workspace,
        ToolResultPort toolResult,
        InteractionChannel interaction,
        EventPublisher events) {
}
