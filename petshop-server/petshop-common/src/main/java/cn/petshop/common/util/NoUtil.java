package cn.petshop.common.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务单号生成。
 *
 * 单号 = 前缀(2) + yyyyMMdd(8) + 随机数(10)。
 * 随机位由 6 位提升到 10 位（对应审查 MINOR-5）：单日同前缀碰撞概率由 ~1e-6 降到 ~1e-10，
 * 避免撞唯一索引直接把请求打成 500。仍需重试兜底的场景（如核销码）由调用方负责，
 * {@code AppointmentService#insertWithUniqueVerifyCode} 已在撞键时重试 5 次。
 */
public final class NoUtil {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 10 位随机数 → 取值空间 10^10 */
    private static final long RAND_LIMIT = 10_000_000_000L;

    private NoUtil() {
    }

    public static String apptNo() {
        return "AP" + LocalDate.now().format(FMT) + rand();
    }

    public static String settleNo() {
        return "ST" + LocalDate.now().format(FMT) + rand();
    }

    public static String payNo() {
        return "PY" + LocalDate.now().format(FMT) + rand();
    }

    public static String cardNo() {
        return "CD" + LocalDate.now().format(FMT) + rand();
    }

    public static String orderNo() {
        return "GO" + LocalDate.now().format(FMT) + rand();
    }

    public static String refundNo() {
        return "RF" + LocalDate.now().format(FMT) + rand();
    }

    private static String rand() {
        // 高位补零：保证定长 10 位，便于人工核对与日志检索
        return String.format("%010d", ThreadLocalRandom.current().nextLong(RAND_LIMIT));
    }
}
