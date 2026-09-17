package cn.petshop.admin.member;

import cn.petshop.admin.member.AdminMemberService;
import cn.petshop.common.annotation.RequireRole;
import cn.petshop.common.context.AdminContext;
import cn.petshop.common.context.StoreContext;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.common.web.R;
import cn.petshop.wallet.entity.CardLedger;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 后台会员管理：检索 / 详情 / 次卡调次 / 健康到期看板。
 *
 * <p>检索、详情、看板都是读操作，登录即可访问 —— 店员接待顾客时要能查余额、查次卡、
 * 查到期的疫苗，把读也锁成店长会让柜台根本没法用。
 * 只有<b>调次</b>要店长：它直接改变顾客已经付过钱的权益。</p>
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminMemberController {

    private final AdminMemberService memberService;

    @GetMapping("/members")
    public R<IPage<AdminMemberVO>> search(@RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return R.ok(memberService.search(StoreContext.DEFAULT_STORE_ID, keyword, page, size));
    }

    @GetMapping("/members/{id}")
    public R<AdminMemberVO> detail(@PathVariable Long id) {
        return R.ok(memberService.detail(id));
    }

    @Data
    public static class AdjustTimesCmd {
        /** 可正可负（正=补次，负=扣次） */
        private Integer times;
        /** 原因，必填 */
        private String remark;
    }

    @PostMapping("/cards/{cardId}/adjust-times")
    @RequireRole(AdminRole.MANAGER)
    public R<Map<String, Object>> adjustTimes(@PathVariable Long cardId,
                                              @RequestBody AdjustTimesCmd cmd) {
        if (cmd == null || cmd.getTimes() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "调整次数不能为空");
        }
        return R.ok(memberService.adjustCardTimes(cardId, cmd.getTimes(), cmd.getRemark(),
                AdminContext.operatorName()));
    }

    /** 某张次卡的完整流水（购买 / 预扣 / 核销 / 退款 / 人工调次）。 */
    @GetMapping("/cards/{cardId}/ledgers")
    public R<List<CardLedger>> cardLedgers(@PathVariable Long cardId,
                                           @RequestParam(defaultValue = "50") long limit) {
        return R.ok(memberService.cardLedgers(cardId, limit));
    }

    /**
     * 宠物健康到期看板。
     *
     * @param windowDays  提前提醒天数，默认 30
     * @param onlyOverdue 只看已逾期
     */
    @GetMapping("/health-board")
    public R<List<AdminMemberVO.HealthDue>> healthBoard(
            @RequestParam(required = false) Integer windowDays,
            @RequestParam(name = "onlyOverdue", defaultValue = "false") boolean onlyOverdue) {
        return R.ok(memberService.healthBoard(StoreContext.DEFAULT_STORE_ID, windowDays, onlyOverdue));
    }
}
