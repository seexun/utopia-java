package tech.seexun.jknow.store;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.mapper.TimeMentionsMapper;
import tech.seexun.jknow.store.mapper.TimeMentionsMapper.TimeMentionRow;

/**
 * 时间提及（time_mentions.rs 的任务 2 切片：record/forFacts）——一条时间提及是字，
 * 不是日期（0044 第一刀，#729）：落库的是那几个字和它在 chunks.text 里的<b>字符</b>
 * 偏移。把字读成日期是 0045 后面几刀的事（任务 8 的解算族）。
 */
@Service
public class TimeMentionsService {

    private final TimeMentionsMapper mentions;

    public TimeMentionsService(TimeMentionsMapper mentions) {
        this.mentions = mentions;
    }

    /**
     * time_mentions.rs:59-85 {@code record}：记一条时间提及。槽只有 when / ended
     * 两个——由库上的 CHECK 把关（0064：role IN ('when','ended')），代码不另拦一道；
     * as_of 不是这条陈述的时间，是读它的人的（时间窗查询参数）。
     */
    public UUID record(UUID kbId, UUID factId, UUID chunkId, String text, int charStart,
                       String role) {
        return mentions.insertMention(Uuids.nowV7(), kbId, factId, chunkId, text, charStart, role);
    }

    /** time_mentions.rs:87-114 {@code for_facts}：一批陈述各自提到的时间词。 */
    public Map<UUID, List<TimeMentionRow>> forFacts(List<UUID> factIds) {
        Map<UUID, List<TimeMentionRow>> out = new HashMap<>();
        if (factIds.isEmpty()) {
            return out;
        }
        for (TimeMentionRow m : mentions.selectForFacts(factIds)) {
            out.computeIfAbsent(m.factId(), k -> new java.util.ArrayList<>()).add(m);
        }
        return out;
    }
}
