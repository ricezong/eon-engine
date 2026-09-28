package cn.kong.eon.engine.msg;

/**
 * 工具规格：暴露给模型的工具描述。
 *
 * @param parametersJsonSchema JSON Schema 形式的参数描述；空 schema 表示无参数
 * @param destructive          破坏性操作默认走门禁审批
 */
public record ToolSpec(String name, String description, String parametersJsonSchema, boolean destructive) {

    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    public static ToolSpec of(String name, String description) {
        return new ToolSpec(name, description, EMPTY_SCHEMA, false);
    }

    public static ToolSpec destructive(String name, String description) {
        return new ToolSpec(name, description, EMPTY_SCHEMA, true);
    }
}
