package cn.petshop.settle.service;

import cn.petshop.booking.entity.Appointment;
import cn.petshop.booking.service.AppointmentService;
import cn.petshop.common.enums.ApptStatus;
import cn.petshop.common.enums.GuaranteeType;
import cn.petshop.common.enums.HealthLogType;
import cn.petshop.common.enums.ServiceCategory;
import cn.petshop.common.util.NoUtil;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.member.entity.Member;
import cn.petshop.member.service.HealthArchiveService;
import cn.petshop.member.service.MemberLevelService;
import cn.petshop.member.service.MemberService;
import cn.petshop.settle.entity.SettleBill;
import cn.petshop.settle.mapper.SettleBillMapper;
import cn.petshop.wallet.service.BalanceService;
import cn.petshop.wallet.service.CardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SettleService {

    private final AppointmentService appointmentService;
    private final BalanceService balanceService;
    private final CardService cardService;
    private final HealthArchiveService healthArchiveService;
    private final MemberLevelService memberLevelService;
    private final MemberService memberService;
    private final SettleBillMapper settleBillMapper;
    private final ObjectMapper objectMapper;

    /**
     * 结算入参。
     *
     * <p><b>没有 operator 字段是有意的</b>：结算会扣余额 / 核销次卡、并写入健康档案，
     * 属于资金动作，操作人必须来自登录态而不是请求体。改由 {@link #confirm} 的独立入参传入。
     */
    @Data
    public static class ConfirmCmd {
        private List<ConfirmItem> items;
        private BigDecimal manualPrice;
        private String manualReason;
        private String payType;
    }

    @Data
    public static class ConfirmItem {
        private String name;
        private String category;
        private BigDecimal amount;
        private BigDecimal weightValue;
    }

    @Data
    public static class ConfirmResult {
        private String settleNo;
        private BigDecimal total;
        private String payType;
        private BigDecimal balancePaid;
        /** 余额/次卡抵扣之外、需要店员线下收取的现金差额（total − 余额实扣 − 次卡覆盖额）。 */
        private BigDecimal cashDue;
    }

    /**
     * 结算确认。
     *
     * @param operator 操作人，由调用方从登录态取得（{@code AdminContext}）。
     *                 结算会动余额 / 次卡，这条留痕必须可信，不接受客户端指定。
     */
    @Transactional
    public ConfirmResult confirm(Long apptId, ConfirmCmd cmd, String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "操作人不能为空（结算必须留痕）");
        }
        String operatorName = operator.trim();
        Appointment appt = appointmentService.loadOrThrow(apptId);
        if (cmd.getItems() == null || cmd.getItems().isEmpty()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "结算明细不能为空");
        }
        // 进入事务先做状态 CAS 抢占（ARRIVED → SETTLING）。
        // 并发双击时：后到的事务会阻塞在行锁上，待前者提交后读到 SETTLED，CAS 返回 0 被拒绝，
        // 从而杜绝重复扣款 / 重复出结算单 / 重复写健康档案（对应审查 M-1）。
        if (appointmentService.casStatus(apptId, ApptStatus.ARRIVED, ApptStatus.SETTLING) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "仅已到店预约可结算，请先核销签到");
        }
        appt.setStatus(ApptStatus.SETTLING.name());
        String payType = cmd.getPayType() == null ? "CASH" : cmd.getPayType();

        Member member = memberService.byId(appt.getMemberId());
        BigDecimal levelDiscount = memberLevelService.discountForMember(appt.getStoreId(), member);
        List<DiscountEngine.BillItem> billItems = cmd.getItems().stream()
                .map(i -> new DiscountEngine.BillItem(i.getName(), i.getCategory(), i.getAmount()))
                .toList();
        DiscountEngine.Bill bill = DiscountEngine.calc(billItems, levelDiscount,
                cmd.getManualPrice(), cmd.getManualReason());

        String settleNo = NoUtil.settleNo();
        BigDecimal balancePaid = BigDecimal.ZERO;
        // 次卡覆盖的金额（担保额快照）。卡担保时 total 超出这部分的差额必须有着落：
        // 要么从余额自动补收，要么由店员线下收现金 —— 结算单与结果都会把差额摆到明面上。
        BigDecimal cardCovered = BigDecimal.ZERO;

        if (GuaranteeType.BALANCE.name().equals(appt.getGuaranteeType())) {
            // 余额担保：冻结转消费，实际扣款以结算单 total 为准（多做少做都按实结算）。
            balanceService.consumeFrozen(appt.getMemberId(), appt.getGuaranteeAmount(), bill.getTotal(), settleNo);
            balancePaid = bill.getTotal();
        } else if (GuaranteeType.CARD.name().equals(appt.getGuaranteeType())) {
            // 次卡担保：预扣的 1 次在此核销。次是"一次服务"不是金额，
            // total 因改价/部分项目低于担保额时也照常核销 1 次（改价不该让顾客损失次数）。
            cardService.confirm(appt.getCardId(), settleNo);
            cardCovered = appt.getGuaranteeAmount();
            BigDecimal extra = bill.getTotal().subtract(cardCovered);
            if (extra.compareTo(BigDecimal.ZERO) > 0) {
                if ("BALANCE".equals(payType)) {
                    // 余额不足时 consumeAvailable 会抛 BIZ_BALANCE_NOT_ENOUGH，整个结算回滚——
                    // 让店员当场知道还差钱，而不是事后对账才发现少收（对应审查 P1-3）。
                    balanceService.consumeAvailable(appt.getMemberId(), extra, settleNo);
                    balancePaid = extra;
                }
                // payType=CASH：差额由店员线下收取，cashDue 会随结果返回并在结算单留痕，
                // 不再静默漏收。
            }
        } else if ("BALANCE".equals(payType)) {
            balanceService.consumeAvailable(appt.getMemberId(), bill.getTotal(), settleNo);
            balancePaid = bill.getTotal();
        }
        BigDecimal cashDue = bill.getTotal().subtract(balancePaid).subtract(cardCovered)
                .max(BigDecimal.ZERO);

        appendHealthLogs(appt, cmd, settleNo, operatorName);

        SettleBill settle = new SettleBill();
        settle.setSettleNo(settleNo);
        settle.setApptId(appt.getId());
        settle.setMemberId(appt.getMemberId());
        settle.setStoreId(appt.getStoreId());
        try {
            settle.setItems(objectMapper.writeValueAsString(cmd.getItems()));
            settle.setDiscountDesc(objectMapper.writeValueAsString(bill));
        } catch (Exception e) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "结算单序列化失败");
        }
        settle.setTotal(bill.getTotal());
        settle.setManualPrice(cmd.getManualPrice());
        settle.setManualReason(cmd.getManualReason());
        // 结算单的 payType 记的是「这笔钱实际从哪来」，而不是操作员在下拉框里选了什么
        // （对应审查 P3-1）：
        // - 余额担保：资金全部来自余额冻结转消费，固定记 BALANCE —— 否则操作员选了 CASH
        //   会出现 payType=CASH 而 balancePaid=total 的自相矛盾，按渠道对账必错。
        // - 次卡担保：主渠道是次卡抵扣，固定记 CARD；超额部分的渠道看 pay_detail
        //   （balancePaid>0 即余额补收，cashDue>0 即现金）。
        // - 无担保：操作员的选择即事实（BALANCE 已自动扣，CASH 线下收）。
        String settlePayType;
        if (GuaranteeType.BALANCE.name().equals(appt.getGuaranteeType())) {
            settlePayType = "BALANCE";
        } else if (GuaranteeType.CARD.name().equals(appt.getGuaranteeType())) {
            settlePayType = "CARD";
        } else {
            settlePayType = payType;
        }
        settle.setPayType(settlePayType);
        settle.setPayDetail("{\"balancePaid\":" + balancePaid
                + ",\"cardCovered\":" + cardCovered
                + ",\"cashDue\":" + cashDue + "}");
        settle.setOperator(operatorName);
        settle.setCreatedAt(java.time.LocalDateTime.now());
        settleBillMapper.insert(settle);

        appointmentService.updateStatus(appt, ApptStatus.SETTLED);

        ConfirmResult result = new ConfirmResult();
        result.setSettleNo(settleNo);
        result.setTotal(bill.getTotal());
        result.setPayType(settlePayType);
        result.setBalancePaid(balancePaid);
        result.setCashDue(cashDue);
        return result;
    }

    /**
     * 结算与健康档案同库同事务：不存在"结算了但档案没动"的中间态。
     */
    private void appendHealthLogs(Appointment appt, ConfirmCmd cmd, String settleNo, String operator) {
        LocalDate today = LocalDate.now();
        List<HealthLogType> written = new ArrayList<>();
        for (ConfirmItem item : cmd.getItems()) {
            HealthLogType type = mapCategory(item.getCategory());
            if (type == null || written.contains(type)) {
                continue;
            }
            LocalDate nextDue = switch (type) {
                case VACCINE -> today.plusYears(1);
                case WORM -> today.plusMonths(3);
                default -> null;
            };
            healthArchiveService.appendServiceLog(appt.getPetId(), type, today, nextDue,
                    item.getWeightValue(), settleNo, operator);
            written.add(type);
        }
    }

    private HealthLogType mapCategory(String category) {
        if (category == null) {
            return null;
        }
        ServiceCategory sc;
        try {
            sc = ServiceCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            // 非法分类应回 400 而非 500（对应审查 MINOR-3）
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "未知服务分类：" + category);
        }
        return switch (sc) {
            case WASH, SPA -> HealthLogType.BATH;
            case GROOM -> HealthLogType.GROOM;
            case VACCINE -> HealthLogType.VACCINE;
            case WORM -> HealthLogType.WORM;
            case BOARD -> null;
        };
    }
}
