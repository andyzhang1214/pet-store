package cn.petshop.wallet.controller;

import cn.petshop.common.context.MemberContext;
import cn.petshop.common.context.StoreContext;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.common.web.R;
import cn.petshop.wallet.entity.PayOrder;
import cn.petshop.wallet.entity.RechargeTier;
import cn.petshop.wallet.service.PayService;
import cn.petshop.wallet.service.RechargeTierService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 充值下单与支付回调。
 *
 * <h3>赠送金额由服务端决定（本次修正）</h3>
 * 改造之前 {@link CreateCmd} 里有 {@code gift} 字段，服务端只校验"不超过本金"——
 * 也就是赠送多少由发起请求的一方说了算。现在该字段已从报文中移除，
 * 赠送金额一律由 {@link RechargeTierService#requireGift} 按本金查启用档位得出，
 * 匹配不到档位则拒绝下单。<b>请求体里已经没有能影响入账金额的字段。</b>
 *
 * <h3>安全约定（对应审查 C-1）</h3>
 *  - {@code /mock-notify} 仅在 {@code petshop.wx.mock=true} 时可用，生产环境必须置 false（默认即为 false）；
 *    开发环境也走完整的归属校验 + 金额对账，与真实回调同一条入账路径，避免"开发路径"绕过风控。
 *  - 入账金额由服务端按订单金额对账，回调传入的 principal/gift 必须与订单一致，否则拒绝。
 */
@RestController
@RequestMapping("/api/v1/recharges")
@RequiredArgsConstructor
public class RechargeController {

    private final PayService payService;
    private final RechargeTierService rechargeTierService;

    /** 默认 false：未显式开启前，模拟回调端点始终不可用。 */
    @Value("${petshop.wx.mock:false}")
    private boolean wxMock;

    /** 充值选项：只返回启用档位，前端据此渲染"充 X 送 Y"。 */
    @GetMapping("/tiers")
    public R<List<RechargeTier>> tiers() {
        return R.ok(rechargeTierService.listEnabled(StoreContext.DEFAULT_STORE_ID));
    }

    /**
     * 下单用的入参。
     *
     * <p>刻意<b>没有</b> {@code gift} 字段：赠送金额不是客户能选的参数。
     * 老客户端若仍在报文里带 gift，会被 Jackson 忽略（Spring Boot 默认不因未知字段报错），
     * 不会影响入账金额，也不会报错 —— 这正是我们要的效果。</p>
     */
    @Data
    public static class CreateCmd {
        private BigDecimal amount;
    }

    @PostMapping
    public R<Map<String, Object>> create(@RequestBody CreateCmd cmd) {
        Long memberId = MemberContext.require();
        if (cmd == null || cmd.getAmount() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "充值金额不能为空");
        }
        BigDecimal principal = cmd.getAmount();
        if (principal.compareTo(new BigDecimal("0.01")) < 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "充值金额必须大于 0");
        }
        // 赠送额由档位配置决定；不在档位内直接拒绝，而不是按 0 赠送 ——
        // 静默按 0 送会让客户以为"充了但没送"，这类争议比一句明确报错贵得多
        BigDecimal gift = rechargeTierService.requireGift(StoreContext.DEFAULT_STORE_ID, principal);

        PayOrder order = payService.createRechargeOrder(memberId, StoreContext.DEFAULT_STORE_ID,
                principal.add(gift), "WXPAY");
        return R.ok(Map.of(
                "payNo", order.getPayNo(),
                "amount", order.getAmount(),
                "principal", principal,
                "gift", gift,
                "payParams", Map.of("mock", wxMock)
        ));
    }

    /**
     * 开发联调用模拟支付回调。门控 + 归属 + 金额对账三重校验，缺一不可。
     * 生产环境（petshop.wx.mock=false）调用恒定失败。
     *
     * <p>注意这里仍然要求传入 principal/gift 与订单对账，
     * 由测试或开发脚本来"扮演微信回调"；生产路径的金额来自验签后的回包。</p>
     */
    @Data
    public static class NotifyCmd {
        private BigDecimal amount;
        private BigDecimal gift;
    }

    @PostMapping("/{payNo}/mock-notify")
    public R<Boolean> mockNotify(@PathVariable String payNo, @RequestBody NotifyCmd cmd) {
        Long memberId = MemberContext.require();
        if (!wxMock) {
            throw new BizException(ErrorCode.BIZ_PERMISSION_DENIED, "模拟支付回调未开启");
        }
        BigDecimal gift = cmd == null || cmd.getGift() == null ? BigDecimal.ZERO : cmd.getGift();
        BigDecimal amount = cmd == null ? null : cmd.getAmount();
        return R.ok(payService.onPaySuccess(payNo, memberId, "MOCK-" + payNo, amount, gift));
    }
}
