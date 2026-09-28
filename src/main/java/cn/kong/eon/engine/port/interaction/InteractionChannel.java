package cn.kong.eon.engine.port.interaction;

import java.time.Duration;
import java.util.List;

import cn.kong.eon.engine.msg.ToolCall;
import cn.kong.eon.engine.msg.ToolSpec;

/**
 * 人机交互端口：ask_question 工具与破坏性门禁的统一出口。
 * ask() 在工具线程上阻塞；答案由外部调用 engine.answer() 投递，经 deliverAnswer() 唤醒。
 */
public interface InteractionChannel {

    /** 提问并阻塞等待答案；超时/取消由实现处理。 */
    QuestionReply ask(Question question, Duration timeout);

    Decision gate(ToolCall call, ToolSpec spec);

    /** 异步通道投递答案（实现方校验 questionId 归属本会话）；同步通道返回 false。 */
    default boolean deliverAnswer(String sessionId, String questionId, List<Answer> answers) {
        return false;
    }

    /** 一次提问可含多个问题，前端单屏作答。 */
    public record Question(String id, String title, List<Ask> asks) {

        public Question {
            title = title == null ? "" : title;
            asks = asks == null ? List.of() : List.copyOf(asks);
        }

        /** multiple=true 允许多选。 */
        public record Ask(String id, String text, List<Option> options, boolean multiple) {

            public Ask {
                options = options == null ? List.of() : List.copyOf(options);
            }
        }

        /** id 供程序判断，label 是展示文案；答案回填 label。 */
        public record Option(String id, String label) {
        }
    }

    sealed interface QuestionReply permits QuestionReply.Answered, QuestionReply.TimedOut, QuestionReply.Cancelled {

        /** 每项按 askId 对应一个问题的答案。 */
        record Answered(List<Answer> answers) implements QuestionReply {

            public Answered {
                answers = answers == null ? List.of() : List.copyOf(answers);
            }
        }

        record TimedOut() implements QuestionReply {
        }

        record Cancelled() implements QuestionReply {
        }
    }

    /** 选中选项的展示文案 + 可选自填文本，两者可同时存在。 */
    record Answer(String askId, List<String> labels, String other) {

        public Answer {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }

    enum Decision {
        APPROVED, REJECTED
    }
}
