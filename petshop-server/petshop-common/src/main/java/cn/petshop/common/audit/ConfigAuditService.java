package cn.petshop.common.audit;

import cn.petshop.common.audit.mapper.ConfigAuditLogMapper;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置变更留痕。
 *
 * <h3>操作人为什么是方法入参</h3>
 * 与 {@code RefundService} 的处理一致：服务层收一个显式的 {@code operator}，
 * 由 Controller 从 {@code AdminContext.operatorName()} 取。
 * 这样测试在没有 HTTP 上下文时也能直接调，而 HTTP 路径上
 * <b>请求体里根本没有这个字段可以伪造</b> —— 留痕的可信度来自它在服务端的取值位置，
 * 而不是来自"我们相信前端会填对"。
 *
 * <h3>{@link #diff} 是这个类的核心</h3>
 * 手写"改了哪几个字段"的判断，第一次就会漏；而且漏掉的那次一定发生在改价上。
 * 把前后值都交给 {@link #diff}，由它比对并<b>只为真正变化的字段落行</b>：
 * 前台点了保存但什么都没改时不会产生噪音记录，也就不会把真正的变更淹掉。
 */
@Service
@RequiredArgsConstructor
public class ConfigAuditService {

    /** 审计表 remark 是 VARCHAR(255) */
    private static final int REMARK_MAX_LEN = 255;
    /** 单次 diff 最多落多少行，防止误传超大 Map 打爆审计表 */
    private static final int MAX_FIELDS_PER_CHANGE = 50;

    private final ConfigAuditLogMapper auditLogMapper;

    /**
     * 比对前后值，只为发生变化的字段落审计行。
     *
     * @param before 变更前字段快照，key 为业务语义字段名（如「价格」）
     * @param after  变更后字段快照
     * @return 实际落库的审计行数
     */
    public int diff(String targetType, Long targetId,
                    Map<String, Object> before, Map<String, Object> after,
                    String remark, String operator) {
        String op = requireOperator(operator);
        if (targetType == null || targetType.isBlank() || targetId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "审计对象不能为空");
        }
        String safeRemark = truncate(remark);
        LocalDateTime now = LocalDateTime.now();
        int written = 0;

        // 用 after 的 key 顺序为主（新增字段通常出现在 after），再补上只在 before 里出现的
        Map<String, Object> keys = new LinkedHashMap<>();
        if (after != null) {
            after.forEach(keys::put);
        }
        if (before != null) {
            before.forEach(keys::putIfAbsent);
        }

        for (Map.Entry<String, Object> e : keys.entrySet()) {
            String b = stringify(before == null ? null : before.get(e.getKey()));
            String a = stringify(after == null ? null : after.get(e.getKey()));
            if (java.util.Objects.equals(b, a)) {
                continue;
            }
            if (++written > MAX_FIELDS_PER_CHANGE) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                        "单次变更字段过多（>" + MAX_FIELDS_PER_CHANGE + "），请拆分提交");
            }
            insert(targetType, targetId, ConfigAuditLog.ACTION_UPDATE,
                    e.getKey(), b, a, safeRemark, op, now);
        }
        return written;
    }

    /** 新建对象：落一行摘要（无前值、无具体字段）。 */
    public void created(String targetType, Long targetId, String summary, String operator) {
        insert(targetType, targetId, ConfigAuditLog.ACTION_CREATE,
                null, null, truncate(summary), null, requireOperator(operator), LocalDateTime.now());
    }

    /** 单字段变更（上下架、启停、库存盘点等"状态/数量"类动作）。 */
    public void single(String targetType, Long targetId, String action,
                       String fieldName, String before, String after, String remark, String operator) {
        insert(targetType, targetId, action, fieldName, before, after,
                truncate(remark), requireOperator(operator), LocalDateTime.now());
    }

    /** 审计查询：按对象查变更历史，倒序。 */
    public List<ConfigAuditLog> list(String targetType, Long targetId, long limit) {
        // 第一个参数是"条件是否生效"开关：targetType 为 null 时不拼条件。
        // 若无条件地 .eq(targetType, null)，MP 会生成 `WHERE target_type = NULL`——
        // 永远匹配不到行，"全部对象"视图就永远 0 条（SQL 的 = NULL 不等于 IS NULL）。
        LambdaQueryWrapper<ConfigAuditLog> q = new LambdaQueryWrapper<ConfigAuditLog>()
                .eq(targetType != null && !targetType.isBlank(), ConfigAuditLog::getTargetType, targetType)
                .orderByDesc(ConfigAuditLog::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        if (targetId != null) {
            q.eq(ConfigAuditLog::getTargetId, targetId);
        }
        return new ArrayList<>(auditLogMapper.selectList(q));
    }

    /** 全局审计流：不限定对象，用于后台"最近变更"页。 */
    public List<ConfigAuditLog> recent(long limit) {
        return list(null, null, limit);
    }

    private void insert(String targetType, Long targetId, String action, String fieldName,
                        String before, String after, String remark, String operator, LocalDateTime now) {
        ConfigAuditLog row = new ConfigAuditLog();
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setAction(action);
        row.setFieldName(fieldName);
        row.setBeforeValue(before);
        row.setAfterValue(after);
        row.setOperator(operator);
        row.setRemark(remark);
        row.setCreatedAt(now);
        auditLogMapper.insert(row);
    }

    /**
     * 空操作人是错误而不是"不记录"。
     *
     * <p>静默跳过会让"没有审计行"同时意味着两件事：真的没改、以及忘了传操作人。
     * 一个会静默丢审计的审计组件，比没有审计更危险。</p>
     */
    private String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "操作人不能为空（配置变更必须留痕）");
        }
        return truncate(operator.trim());
    }

    private String stringify(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private String truncate(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > REMARK_MAX_LEN ? s.substring(0, REMARK_MAX_LEN) : s;
    }
}
