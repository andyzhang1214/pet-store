package cn.petshop.mall.controller;

import cn.petshop.common.context.StoreContext;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.common.web.R;
import cn.petshop.mall.job.OrderExpiryJob;
import cn.petshop.mall.service.MallOrderService;
import cn.petshop.mall.service.OrderVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台商品订单：列表查询与自提核销。
 * {@code /admin/**} 由 petshop-admin 的 AdminTokenInterceptor 统一鉴权。
 *
 * <p>核销端点刻意与预约签到（{@code POST /admin/verify}）<b>分开</b>：两类核销码都是 8 位数字、
 * 且分别存在于 appointment 与 goods_order 两张表，共用一个入口会需要"先试哪张表"的猜测逻辑，
 * 一旦猜错就会把商品码当成预约码报错。后台界面上也是两个独立操作。
 */
@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final MallOrderService orderService;
    private final OrderExpiryJob orderExpiryJob;

    /** 订单列表。传 status=PAID 即待自提队列。 */
    @GetMapping
    public R<IPage<OrderVO>> list(@RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "20") long size) {
        return R.ok(orderService.listForAdmin(StoreContext.DEFAULT_STORE_ID, status, page, size));
    }

    @Data
    public static class VerifyCmd {
        private String code;
    }

    /** 自提核销：按 8 位核销码把订单置为已核销。 */
    @PostMapping("/verify")
    public R<OrderVO> verify(@RequestBody VerifyCmd cmd) {
        if (cmd == null || cmd.getCode() == null || cmd.getCode().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "核销码不能为空");
        }
        return R.ok(orderService.verifyByCode(cmd.getCode().trim()));
    }

    /**
     * 手动触发一轮超时关单，返回本轮关闭的笔数。
     *
     * <p>定时任务正常跑的时候用不到它。留这个入口是为了应对两种情况：
     * 一是部署环境把 {@code expiry-enabled} 关掉了，需要人工兜一次；
     * 二是改完关单阈值想立刻生效，不想等下一次扫描。
     *
     * <p>阈值与批量都由调用方传入并带上限，不允许"传 0 就是不限"这类含糊语义 ——
     * 未传时沿用配置里的默认值，语义始终是"按门店既定规则关单"。
     */
    @Data
    public static class CloseExpiredCmd {
        private Integer timeoutMinutes;
        private Integer batchSize;
    }

    @PostMapping("/close-expired")
    public R<Integer> closeExpired(@RequestBody(required = false) CloseExpiredCmd cmd) {
        int minutes = cmd == null || cmd.getTimeoutMinutes() == null
                ? orderExpiryJob.timeoutMinutesOrDefault()
                : cmd.getTimeoutMinutes();
        int batch = cmd == null || cmd.getBatchSize() == null
                ? orderExpiryJob.batchSizeOrDefault()
                : cmd.getBatchSize();
        if (minutes <= 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "关单阈值必须大于 0 分钟");
        }
        return R.ok(orderExpiryJob.expire(minutes, batch));
    }
}
