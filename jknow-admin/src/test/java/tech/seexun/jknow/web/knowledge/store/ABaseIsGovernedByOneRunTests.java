package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GovernanceService;

/**
 * 移植自 tests/a_base_is_governed_by_one_run.rs（35 行）——一个库同一时刻只有一个
 * 治理任务（会话级咨询锁）。第二个任务抢不到锁就退出并晚点再排，而不是和第一个
 * 一起读同一个队头、把同一簇裁两遍。
 *
 * <p>锁不落任何行（只拿 PG 会话咨询锁），不建 fixture、不清理——随机 kb id 与
 * 现存任何库不相干。
 */
class ABaseIsGovernedByOneRunTests extends HeartStoreTestBase {

    @Autowired
    GovernanceService governance;

    @Test
    void theSecondRunOnABaseDoesNotGetTheLockUntilTheFirstReleasesIt() {
        UUID kb = Uuids.nowV7();
        UUID other = Uuids.nowV7();

        GovernanceService.BaseLock first = governance.tryLockBase(kb);
        assertNotNull(first, "a free base is locked at once");
        assertNull(governance.tryLockBase(kb), "a second run on the same base must not get the lock");
        GovernanceService.BaseLock otherBase = governance.tryLockBase(other);
        assertNotNull(otherBase, "another base is another lock");
        // Rust 原测试靠 pool.close() 收掉这把锁；共享池不能整池关闭，显式放掉等价收尾
        otherBase.release();
        first.release();
        GovernanceService.BaseLock again = governance.tryLockBase(kb);
        assertNotNull(again, "released, the base can be governed again");
        again.release();
    }
}
