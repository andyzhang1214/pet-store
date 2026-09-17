package cn.petshop.settle.controller;

import cn.petshop.common.annotation.RequireRole;
import cn.petshop.common.context.AdminContext;
import cn.petshop.common.context.StoreContext;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.web.R;
import cn.petshop.settle.service.RefundService;
import cn.petshop.wallet.entity.RefundApply;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台退款审核与人工退款。/admin/** 由 petshop-admin 的 AdminTokenInterceptor 统一鉴权。
 *
 * 口径：退款只入会员可用余额，审核通过即到账（无"处理中"中间态）。
 *
 * <p>审核与人工退款都要求<b>店长</b>角色（{@link RequireRole}）。
 * 这两种操作会真实移动资金且不可撤销，是后台权限里唯一真正需要分级的地方。
 * 列表与待审计数不设角色限制：店员需要看得到"有退款在等"，只是点不了通过。
 *
 * <p>操作人一律取自 {@link AdminContext}（登录态），不再从请求体读取 ——
 * 客户端能指定审核人，留痕就没有证据价值。
 */
@RestController
@RequestMapping("/admin/refunds")
@RequiredArgsConstructor
public class AdminRefundController {

    private final RefundService refundService;

    /** 待审数量。后台角标轮询用：店长不必主动打开列表就知道「有 N 笔待处理」。 */
    @GetMapping("/pending-count")
    public R<Long> pendingCount() {
        return R.ok(refundService.pendingCount(StoreContext.DEFAULT_STORE_ID));
    }

    /** 退款单列表。传 status=APPLYING 即待审队列。 */
    @GetMapping
    public R<IPage<RefundApply>> list(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size) {
        return R.ok(refundService.listForAudit(StoreContext.DEFAULT_STORE_ID, status, page, size));
    }

    /** 审核：approved=true 退入余额，false 驳回（必须填理由）。限店长。 */
    @PostMapping("/{id}/audit")
    @RequireRole(AdminRole.MANAGER)
    public R<RefundApply> audit(@PathVariable Long id, @RequestBody RefundService.AuditCmd cmd) {
        return R.ok(refundService.audit(id, cmd, AdminContext.operatorName()));
    }

    /** 店长人工退款：直接生效，用于客诉补偿等无法走单据的场景。限店长。 */
    @PostMapping("/manual")
    @RequireRole(AdminRole.MANAGER)
    public R<RefundApply> manual(@RequestBody RefundService.ManualCmd cmd) {
        return R.ok(refundService.manualRefund(StoreContext.DEFAULT_STORE_ID, cmd, AdminContext.operatorName()));
    }
}
