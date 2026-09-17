package cn.petshop.mall.controller;

import cn.petshop.common.context.MemberContext;
import cn.petshop.common.context.StoreContext;
import cn.petshop.common.web.R;
import cn.petshop.mall.service.GoodsVO;
import cn.petshop.mall.service.MallOrderService;
import cn.petshop.mall.service.MallService;
import cn.petshop.mall.service.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会员侧商城：商品浏览、下单、支付、订单查询。
 * {@code /api/v1/**} 由 petshop-admin 的 JwtInterceptor 统一鉴权，资源归属在 service 内校验。
 */
@RestController
@RequestMapping("/api/v1/mall")
@RequiredArgsConstructor
public class MallController {

    private final MallService mallService;
    private final MallOrderService orderService;

    /** 上架商品列表（含 SKU）。 */
    @GetMapping("/goods")
    public R<List<GoodsVO>> goods() {
        return R.ok(mallService.listOnSale());
    }

    @GetMapping("/goods/{id}")
    public R<GoodsVO> goodsDetail(@PathVariable Long id) {
        return R.ok(mallService.detail(id));
    }

    /** 下单：只提交 skuId + qty，金额由服务端按 SKU 现价计算。 */
    @PostMapping("/orders")
    public R<OrderVO> create(@RequestBody MallOrderService.CreateCmd cmd) {
        return R.ok(orderService.create(MemberContext.require(), StoreContext.DEFAULT_STORE_ID, cmd));
    }

    /** 支付。当前仅余额支付可用，微信支付会收到明确拒绝。 */
    @PostMapping("/orders/{orderNo}/pay")
    public R<OrderVO> pay(@PathVariable String orderNo,
                          @RequestBody(required = false) MallOrderService.PayCmd cmd) {
        return R.ok(orderService.pay(MemberContext.require(), orderNo, cmd));
    }

    /** 取消待支付订单，库存即时回补。 */
    @PostMapping("/orders/{orderNo}/cancel")
    public R<OrderVO> cancel(@PathVariable String orderNo) {
        return R.ok(orderService.cancel(MemberContext.require(), orderNo));
    }

    /** 我的订单（含明细与可退标记）。 */
    @GetMapping("/orders")
    public R<List<OrderVO>> myOrders() {
        return R.ok(orderService.listMine(MemberContext.require()));
    }

    @GetMapping("/orders/{orderNo}")
    public R<OrderVO> orderDetail(@PathVariable String orderNo) {
        return R.ok(orderService.detail(MemberContext.require(), orderNo));
    }
}
