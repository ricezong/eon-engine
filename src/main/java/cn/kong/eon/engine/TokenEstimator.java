package cn.kong.eon.engine;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Token 估算器：o200k_base BPE 计数（jtokkit）。只要求一致且偏保守，水位判档够用即可；
 * 预算熔断走 {@code LlmReply.usage} 的真实用量，不经本类。
 */
public final class TokenEstimator {

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();

    private final Encoding encoding = REGISTRY.getEncoding(EncodingType.O200K_BASE);

    /** null/空返回 0。 */
    public long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }
}
