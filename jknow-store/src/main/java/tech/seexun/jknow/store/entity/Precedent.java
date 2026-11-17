package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 台账里的一笔人的决定（governance.rs:29-47 Precedent）：先例只认人的（actor 不为空）。
 * agent 自己裁过的不算——否则它引用自己，越判越自信。人接受了 agent 的建议算：
 * 那一笔是人签的，走的是人的裁决路径。
 */
public record Precedent(
        UUID eventId,
        /** review.merge | review.keep | merge.manual | merge.revert */
        String action,
        String left,
        String right,
        OffsetDateTime at,
        /** 人拍板时写的那一句（0026）。先例带着理由才是先例：只有结果的话，一次错误的合并会被读成「这类该合」，错误洗成政策。老行没有这一列，为 null */
        String why) {

    /** 这一笔是把两个记录合了，还是分开了（governance.rs:42-47）。 */
    public boolean merged() {
        return "review.merge".equals(action) || "merge.manual".equals(action);
    }
}
