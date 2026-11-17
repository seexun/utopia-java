package tech.seexun.jknow.store.temporal;

import java.util.Comparator;
import java.util.UUID;

/**
 * temporal.rs:68-89 {@code Timeline}：一条唯一性时间线——谁（持有者）在哪个谓词、
 * 哪个方向上同时只能有一个值。{@code bothSides}：谓词两侧都唯一（functional 且
 * inverse functional）——这条线上的每一行也在另一侧的一条线上，重算一侧要读另一侧。
 */
public record Timeline(UUID holder, UUID predicateId, Side side, boolean bothSides) {

    /**
     * Rust uuid 的 Ord 是 16 字节字典序；Java UUID.compareTo 按有符号 long 比较，
     * 与之不同。时间线内的行在 (时刻, 无起点在后) 并列时按 id 排——为了让 Java 排出
     * 与 Rust 逐字节一致的顺序（顺序无关性 #679 的复现测试靠它），这里用字节序比较。
     */
    public static final Comparator<UUID> UUID_BYTES = (a, b) -> {
        byte[] x = toBytes(a);
        byte[] y = toBytes(b);
        for (int i = 0; i < 16; i++) {
            int d = (x[i] & 0xff) - (y[i] & 0xff);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    };

    private static byte[] toBytes(UUID id) {
        byte[] out = new byte[16];
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (msb & 0xff);
            out[8 + i] = (byte) (lsb & 0xff);
            msb >>>= 8;
            lsb >>>= 8;
        }
        return out;
    }

    /**
     * temporal.rs:79-88 {@code lock_key} 逐字：双唯一的关系一条谓词一把锁
     * （重算一侧要读另一侧）；否则按持有者×谓词×侧别。
     */
    public String lockKey(UUID kbId) {
        if (bothSides) {
            return "timeline:" + kbId + ":" + predicateId + ":both";
        }
        return "timeline:" + kbId + ":" + holder + ":" + predicateId + ":" + side.debugName();
    }
}
