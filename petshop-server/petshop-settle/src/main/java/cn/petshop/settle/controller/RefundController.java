package cn.petshop.settle.controller;

import cn.petshop.common.context.MemberContext;
import cn.petshop.common.context.StoreContext;
import cn.petshop.common.web.R;
import cn.petshop.settle.service.RefundService;
import cn.petshop.wallet.entity.RefundApply;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会员侧退款。退款只入可用余额，因此申请只需指定来源单据，金额由服务端计算。
 * {@code /api/v1/**} 由 petshop-admin 的 JwtInterceptor 统一鉴权。
 */
@RestController
@RequestMapping("/api/v1/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    /** 发起退款申请（如次卡整卡退款）。 */
    @PostMapping
    public R<RefundApply> apply(@RequestBody RefundService.ApplyCmd cmd) {
        return R.ok(refundService.apply(MemberContext.require(), StoreContext.DEFAULT_STORE_ID, cmd));
    }

    /** 我的退款单（含历史终态）。 */
    @GetMapping
    public R<List<RefundApply>> list() {
        return R.ok(refundService.listMine(MemberContext.require()));
    }

    /** 撤回待审申请。 */
    @PostMapping("/{id}/cancel")
    public R<RefundApply> cancel(@PathVariable Long id) {
        return R.ok(refundService.cancel(MemberContext.require(), id));
    }
}
