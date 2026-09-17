package cn.petshop.wallet.service;

import cn.petshop.common.audit.ConfigAuditLog;
import cn.petshop.common.audit.ConfigAuditService;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.wallet.entity.Card;
import cn.petshop.wallet.entity.CardType;
import cn.petshop.wallet.mapper.CardMapper;
import cn.petshop.wallet.mapper.CardTypeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台次卡类型配置。
 *
 * <h3>为什么改价、改次数是安全的</h3>
 * {@code card} 表在发卡时把 {@code total_times} 与 {@code paid_amount} 都<b>快照</b>了下来，
 * 退款也按 {@code paid_amount}（购买实付）而不是 {@code card_type.price}。
 * 所以后台调价只影响之后新售出的卡，已售卡不漂移 ——
 * 这正是"允许后台直接改价"的前提；否则每次调价都得新建一个卡类型再停用旧的。
 *
 * <h3>但 times 有一条硬约束</h3>
 * 不允许把已售出卡的总次数改到低于<b>该卡已使用次数</b>：那会让老卡下次核销时
 * {@code remain_times} 直接变成负数。这类脏数据在核销台才会暴露，那时已经没人记得原因了。
 */
@Service
@RequiredArgsConstructor
public class AdminCardTypeService {

    /** 次卡有效期上限（月），防止误填 1200 让卡变成永久卡 */
    private static final int MAX_VALID_MONTHS = 120;
    private static final int MAX_TIMES = 200;

    private static final String VALID_MODE_MONTHS = "MONTHS";
    private static final String VALID_MODE_FIXED = "FIXED";
    private static final Set<String> VALID_MODES = Set.of(VALID_MODE_MONTHS, VALID_MODE_FIXED);

    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";

    private final CardTypeMapper cardTypeMapper;
    private final CardMapper cardMapper;
    private final ConfigAuditService auditService;

    public List<CardType> listCardTypes(Long storeId, boolean includeDisabled) {
        LambdaQueryWrapper<CardType> q = new LambdaQueryWrapper<CardType>()
                .eq(CardType::getStoreId, storeId)
                .orderByAsc(CardType::getId);
        if (!includeDisabled) {
            q.eq(CardType::getStatus, STATUS_ENABLED);
        }
        return new ArrayList<>(cardTypeMapper.selectList(q));
    }

    @Data
    public static class CardTypeCmd {
        private String name;
        /** 适用服务 id，逗号分隔或 JSON 数组文本 */
        private String serviceIds;
        /** 适用规格 id，逗号分隔或 JSON 数组文本 */
        private String specIds;
        private Integer times;
        private BigDecimal price;
        /** MONTHS（按购买日起 N 个月）/ FIXED（固定到期日） */
        private String validMode;
        private Integer validMonths;
        private LocalDate validUntil;
    }

    @Transactional
    public CardType createCardType(Long storeId, CardTypeCmd cmd, String operator) {
        validate(cmd, true);
        CardType type = new CardType();
        type.setStoreId(storeId);
        applyCmd(type, cmd);
        // 新建先停售：等价格与适用范围确认后再上架，避免半成品卡型被会员买走
        type.setStatus(STATUS_DISABLED);
        cardTypeMapper.insert(type);

        auditService.created(ConfigAuditLog.TARGET_CARD_TYPE, type.getId(),
                "新建次卡类型：" + type.getName() + "（" + type.getTimes() + " 次 / "
                        + plain(type.getPrice()) + " 元）", operator);
        return type;
    }

    @Transactional
    public CardType updateCardType(Long cardTypeId, CardTypeCmd cmd, String operator) {
        CardType type = requireCardType(cardTypeId);
        validate(cmd, false);

        Map<String, Object> before = snapshot(type);
        applyCmd(type, cmd);
        assertTimesNotBelowUsed(type);
        cardTypeMapper.updateById(type);

        auditService.diff(ConfigAuditLog.TARGET_CARD_TYPE, cardTypeId, before, snapshot(type), null, operator);
        return type;
    }

    /** 上架 / 停售。停售后 {@code CardService.purchase} 会拒绝新购买，已售出的卡照常使用。 */
    @Transactional
    public CardType setStatus(Long cardTypeId, String status, String operator) {
        CardType type = requireCardType(cardTypeId);
        String target = status == null ? "" : status.trim().toUpperCase();
        if (!STATUS_ENABLED.equals(target) && !STATUS_DISABLED.equals(target)) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "状态只能是 ENABLED 或 DISABLED");
        }
        if (target.equals(type.getStatus())) {
            return type;
        }
        String before = type.getStatus();
        type.setStatus(target);
        cardTypeMapper.updateById(type);
        auditService.single(ConfigAuditLog.TARGET_CARD_TYPE, cardTypeId, ConfigAuditLog.ACTION_STATUS,
                "状态", before, target, null, operator);
        return type;
    }

    /** 该卡型已售出多少张（含已退款）。停售前给店主一个参考数字。 */
    public long soldCount(Long cardTypeId) {
        return cardMapper.selectCount(new LambdaQueryWrapper<Card>()
                .eq(Card::getCardTypeId, cardTypeId));
    }

    // ----------------------------------------------------------------- 内部

    private void applyCmd(CardType type, CardTypeCmd cmd) {
        if (cmd.getName() != null && !cmd.getName().isBlank()) {
            type.setName(cmd.getName().trim());
        }
        if (cmd.getServiceIds() != null) {
            type.setServiceIds(normalizeJsonArray(cmd.getServiceIds()));
        }
        if (cmd.getSpecIds() != null) {
            type.setSpecIds(normalizeJsonArray(cmd.getSpecIds()));
        }
        if (cmd.getTimes() != null) {
            type.setTimes(cmd.getTimes());
        }
        if (cmd.getPrice() != null) {
            type.setPrice(cmd.getPrice());
        }
        if (cmd.getValidMode() != null && !cmd.getValidMode().isBlank()) {
            type.setValidMode(cmd.getValidMode().trim().toUpperCase());
        }
        if (cmd.getValidMonths() != null) {
            type.setValidMonths(cmd.getValidMonths());
        }
        if (cmd.getValidUntil() != null) {
            type.setValidUntil(cmd.getValidUntil());
        }
        // 两种有效期模式互斥：FIXED 用 valid_until，MONTHS 用 valid_months。
        // 同时留着两个值会让 CardService.purchase 的取值分支变得不可预测，这里清掉无关的那个。
        if (VALID_MODE_FIXED.equals(type.getValidMode())) {
            type.setValidMonths(null);
        } else {
            type.setValidUntil(null);
            if (type.getValidMode() == null) {
                type.setValidMode(VALID_MODE_MONTHS);
            }
        }
    }

    private void validate(CardTypeCmd cmd, boolean create) {
        if (cmd == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请求体不能为空");
        }
        if (create && (cmd.getName() == null || cmd.getName().isBlank())) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "卡名称不能为空");
        }
        if (cmd.getTimes() != null && (cmd.getTimes() < 1 || cmd.getTimes() > MAX_TIMES)) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "次数需在 1-" + MAX_TIMES + " 之间");
        }
        if (create && cmd.getTimes() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "次数不能为空");
        }
        if (cmd.getPrice() != null && cmd.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "售价必须大于 0");
        }
        if (create && cmd.getPrice() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "售价不能为空");
        }
        if (cmd.getValidMode() != null && !cmd.getValidMode().isBlank()
                && !VALID_MODES.contains(cmd.getValidMode().trim().toUpperCase())) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "有效期模式只能是 MONTHS 或 FIXED");
        }
        if (cmd.getValidMonths() != null
                && (cmd.getValidMonths() < 1 || cmd.getValidMonths() > MAX_VALID_MONTHS)) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "有效月数需在 1-" + MAX_VALID_MONTHS + " 之间");
        }
        if (VALID_MODE_FIXED.equalsIgnoreCase(cmd.getValidMode())) {
            if (cmd.getValidUntil() == null) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "固定到期日模式必须填写到期日");
            }
            if (cmd.getValidUntil().isBefore(LocalDate.now())) {
                // 发出去就过期的卡，买了立刻不能核销
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "到期日不能早于今天");
            }
        }
    }

    /**
     * 次数不得低于任何一张已售卡的已使用次数。
     *
     * <p>已使用次数 = {@code total_times - remain_times}。</p>
     */
    private void assertTimesNotBelowUsed(CardType type) {
        List<Card> cards = cardMapper.selectList(new LambdaQueryWrapper<Card>()
                .eq(Card::getCardTypeId, type.getId()));
        for (Card card : cards) {
            int used = (card.getTotalTimes() == null ? 0 : card.getTotalTimes())
                    - (card.getRemainTimes() == null ? 0 : card.getRemainTimes());
            if (type.getTimes() != null && type.getTimes() < used) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                        "该卡型已有客户使用 " + used + " 次，总次数不能调到 " + type.getTimes()
                                + "（会让在用的卡剩余次数变成负数）。如需减少次数，请新建一个卡型并停售本卡型。");
            }
        }
    }

    private Map<String, Object> snapshot(CardType type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("名称", type.getName());
        m.put("适用服务", type.getServiceIds());
        m.put("适用规格", type.getSpecIds());
        m.put("次数", type.getTimes());
        m.put("售价", type.getPrice());
        m.put("有效期模式", type.getValidMode());
        m.put("有效月数", type.getValidMonths());
        m.put("固定到期日", type.getValidUntil());
        m.put("状态", type.getStatus());
        return m;
    }

    private CardType requireCardType(Long cardTypeId) {
        if (cardTypeId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "卡类型 ID 不能为空");
        }
        CardType type = cardTypeMapper.selectById(cardTypeId);
        if (type == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "次卡类型不存在");
        }
        return type;
    }

    /**
     * 把 {@code 101,102} 或 {@code [101,102]} 统一成 {@code [101,102]}。
     *
     * <p>库里 seed 用的是 JSON 数组文本，而卡片适用范围的消费方
     * （{@code CardService} 判断卡是否适用于某服务）按文本包含匹配。
     * 统一成方括号形式，避免出现 {@code "101"} 与 {@code "[101]"} 两种写法并存、互相匹配不上。
     */
    private String normalizeJsonArray(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        List<String> parts = new ArrayList<>();
        for (String part : s.split(",")) {
            String v = part.trim().replaceAll("^\"|\"$", "");
            if (!v.isBlank()) {
                parts.add(v);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "[" + String.join(",", parts) + "]";
    }

    private String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}
