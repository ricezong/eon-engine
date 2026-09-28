package cn.kong.eon.engine.msg;

/**
 * 模型发起的一次工具调用。
 *
 * @param argumentsJson 原始参数 JSON 字符串——内核不解析，由工具自行处理
 */
public record ToolCall(String id, String name, String argumentsJson) {

    /** 参数是否满足合法 JSON 的必要条件（字符串闭合、括号配对）——只会漏判不会误杀。 */
    public boolean replayable() {
        String s = argumentsJson;
        if (s == null || s.isBlank()) {
            return true;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{' || c == '[') {
                    depth++;
                } else if ((c == '}' || c == ']') && --depth < 0) {
                    return false;
                }
            }
        }
        return !inString && depth == 0;
    }
}
