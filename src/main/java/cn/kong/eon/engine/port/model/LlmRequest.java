package cn.kong.eon.engine.port.model;

import java.util.List;

import cn.kong.eon.engine.msg.ChatMsg;
import cn.kong.eon.engine.msg.ToolSpec;

public record LlmRequest(List<ChatMsg> messages, List<ToolSpec> tools) {

}
