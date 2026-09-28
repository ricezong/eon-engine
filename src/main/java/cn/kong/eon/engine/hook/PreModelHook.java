package cn.kong.eon.engine.hook;

import cn.kong.eon.engine.loop.TurnContext;

/** 模型调用前；上下文压缩不经钩子，已内置于 context 管线。 */
public interface PreModelHook extends Hook {

    HookResult beforeModel(TurnContext turn);
}
