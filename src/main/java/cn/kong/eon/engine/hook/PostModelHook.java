package cn.kong.eon.engine.hook;

import cn.kong.eon.engine.loop.TurnContext;

public interface PostModelHook extends Hook {

    HookResult afterModel(TurnContext turn);
}
