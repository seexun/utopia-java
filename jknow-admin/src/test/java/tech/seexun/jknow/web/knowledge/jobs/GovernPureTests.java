package tech.seexun.jknow.web.knowledge.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tech.seexun.jknow.store.GovernanceService;
import tech.seexun.jknow.store.GovernanceService.NameShape;

/**
 * governance.rs:1019-1042 内联 mod tests（settled_shape 一例，断言不改写）：形状的疑点
 * 在第二层读过证据之后就答完了——闸门按形状拦的只剩没看过证据的第一层。
 */
class GovernPureTests {

    @Test
    void aDoubtfulShapeStopsCountingOnceTheEvidenceWasRead() {
        NameShape lease = GovernanceService.nameShape(
                "Lease Agreement dated May 16, 2016, as amended", "Lease Agreement");
        assertTrue(GovernJob.nameDoubts(lease), () -> lease.toString());
        assertEquals(lease, GovernJob.settledShape(lease, false));
        assertEquals(NameShape.UNRELATED, GovernJob.settledShape(lease, true));

        NameShape version = GovernanceService.nameShape("Claude Mythos 5", "Claude Mythos");
        assertTrue(GovernJob.nameDoubts(version), () -> version.toString());
        assertEquals(version, GovernJob.settledShape(version, false));

        // 形状本来就不起疑的，读没读过证据都照旧
        NameShape same = GovernanceService.nameShape("OpenAI", "OpenAI");
        assertFalse(GovernJob.nameDoubts(same));
        assertEquals(same, GovernJob.settledShape(same, true));
    }
}
