package tech.seexun.jknow.common.uuids;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 生成（对等旧仓库 uuid::Uuid::now_v7 的用法：org/user/workspace/kb/审计主键）。
 * JDK 无内置 v7，按 RFC 9562 实现：48 位 unix 毫秒 + ver=7 + var=10 + 74 位随机。
 * 时间有序主键的语义（B-tree 局部性、审计可按 id 近似排序）与 Rust 侧一致。
 *
 * <p><b>同一毫秒内单调（任务 11 对齐修复）</b>：Rust 侧 uuid 1.26 的 {@code now_v7()}
 * 自 1.11 起带同毫秒递增计数——同毫秒内后生成的 id 字节序更大，调用序即 id 序。
 * 本侧此前同毫秒全随机，先例是 a_cycle_is_keyed_by_all_its_facts：六个事实一毫秒内建完，
 * 「按 id 先后插：A→B 最小」的前提在 Rust 成立、在本侧是掷硬币（干净 clone 复验首抓——
 * 同毫秒翻转让 canonical 旋转起点换人）。修法与 Rust 同型：毫秒不变时在随机位上递增。
 */
public final class Uuids {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 同毫秒单调状态：上一枚的毫秒与随机位（volatile 读 + synchronized 写，生成频率低）。 */
    private static volatile long lastMs = Long.MIN_VALUE;
    private static long lastRand = 0;

    private Uuids() {
    }

    /** 当前时刻的 UUIDv7（同一毫秒内单调递增——调用序即字节序）。 */
    public static synchronized UUID nowV7() {
        long ms = System.currentTimeMillis();
        long rand;
        if (ms == lastMs) {
            // 同毫秒：随机位 +1（Rust crate 的 counter 同型）；62 位余量远超单毫秒调用量
            rand = lastRand + 1;
            if (rand > 0x3FFFFFFFFFFFFFFFL) {
                // 溢出才挪下一毫秒（62 位同毫秒连续调用在物理上到不了，防御性兜底）
                while ((ms = System.currentTimeMillis()) == lastMs) {
                    Thread.yield();
                }
                rand = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL;
            }
        } else {
            rand = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL;
        }
        lastMs = ms;
        lastRand = rand;
        long msb = (ms & 0xFFFFFFFFFFFFL) << 16;   // 48 位时间戳占高 48 位
        msb |= 0x7000L;                            // version 7
        long lsb = rand | 0x8000000000000000L;     // variant 10 + 随机位
        return new UUID(msb, lsb);
    }
}
