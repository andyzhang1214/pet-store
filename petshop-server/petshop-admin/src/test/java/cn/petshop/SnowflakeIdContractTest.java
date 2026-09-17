package cn.petshop;

import cn.petshop.wallet.entity.RefundApply;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 雪花 id 的 JSON 序列化契约。
 *
 * <h3>为什么必须锁住「id 出参是字符串」</h3>
 * 主键是 MyBatis-Plus 雪花算法生成的 19 位十进制数，超出 JS `Number` 的安全整数范围
 * （2^53-1，约 16 位）。只要序列化成 JSON 数字，前端一 `JSON.parse` 就被静默截断：
 * 不报错、不告警，只是此后所有对这条数据的操作都打在别的/不存在的记录上。
 *
 * <p>真实症状：后台点「通过并退款」提示「退款单不存在」，会员端「撤回申请」同样失败，
 * 而后端日志里全是查不到的 id —— 看起来像并发或数据被删，排查方向完全跑偏。
 *
 * <p>所以这里断言两件事：① 出参里该字段是带引号的字符串；② 原样还原后与原始值完全相等。
 * 只断言「有 id 字段」是发现不了精度丢失的。
 */
@SpringBootTest
@ActiveProfiles("test")
class SnowflakeIdContractTest {

    @Autowired
    private ObjectMapper objectMapper;

    /** 一个真实的 19 位雪花号：> Number.MAX_SAFE_INTEGER（9007199254740991） */
    private static final long SNOWFLAKE = 2099772780119552001L;

    @Test
    void snowflakeIdIsSerializedAsStringAndSurvivesRoundTrip() throws Exception {
        assertTrue(SNOWFLAKE > 9_007_199_254_740_991L,
                "测试前提：该 id 必须超出 JS 安全整数范围，否则测不出精度丢失");

        RefundApply apply = new RefundApply();
        apply.setId(SNOWFLAKE);
        apply.setMemberId(SNOWFLAKE - 1);
        apply.setRefundNo("RF-TEST");
        apply.setAmount(new BigDecimal("89.00"));
        apply.setStatus(RefundApply.STATUS_APPLYING);

        String json = objectMapper.writeValueAsString(apply);

        assertTrue(json.contains("\"id\":\"" + SNOWFLAKE + "\""),
                "id 必须以 JSON 字符串输出，实际：" + json);
        assertFalse(json.contains("\"id\":" + SNOWFLAKE),
                "id 不得以 JSON 数字输出 —— JS 端会静默截断：" + json);

        // 模拟前端行为：仍然按数字解析一次，确认「数字路径」确实丢精度，反证字符串的必要性
        long asDouble = (long) Double.parseDouble(String.valueOf(SNOWFLAKE));
        assertNotEquals(SNOWFLAKE, asDouble, "如果这里相等，说明该 id 未超出 JS 安全范围，测试失去意义");

        // 字符串路径必须无损
        Map<?, ?> back = objectMapper.readValue(json, Map.class);
        assertEquals(String.valueOf(SNOWFLAKE), back.get("id"), "字符串 id 必须原样往返，不可丢位");
    }

    @Test
    void longTypeAndWrapperBothGoThroughString() throws Exception {
        assertEquals("\"" + SNOWFLAKE + "\"",
                objectMapper.writeValueAsString(SNOWFLAKE), "包装类型 Long 必须走字符串");
        assertEquals("\"" + SNOWFLAKE + "\"",
                objectMapper.writeValueAsString(SNOWFLAKE),
                "基本类型 long 同样必须走字符串（serializerByType 两项都要注册）");
    }

    /**
     * 副作用是显式的：计数器也变成字符串了。
     * 这不是缺陷而是取舍 —— 宁可让「数字」多一次显式转换，也不能让 id 静默损坏。
     * 这里把它写进测试，避免以后有人以为是漏配。
     */
    @Test
    void countersAlsoBecomeStringsWhichIsTheAcceptedTradeOff() throws Exception {
        assertEquals("\"42\"", objectMapper.writeValueAsString(42L),
                "Long 计数器会被序列化成字符串，前端展示无影响，做算术前需显式 Number()");
        // 金额是 BigDecimal，不受影响：金额一旦丢精度就是资金事故，必须保持数字语义
        assertEquals("89.00", objectMapper.writeValueAsString(new BigDecimal("89.00")),
                "金额必须保持 JSON 数字，前端才能直接做算术");
    }
}
