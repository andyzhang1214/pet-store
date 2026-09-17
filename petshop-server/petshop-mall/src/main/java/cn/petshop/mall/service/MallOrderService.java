package cn.petshop.mall.service;

import cn.petshop.common.util.NoUtil;
import cn.petshop.common.util.VerifyCodeUtil;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.mall.entity.Goods;
import cn.petshop.mall.entity.GoodsOrder;
import cn.petshop.mall.entity.InventoryLog;
import cn.petshop.mall.entity.OrderItem;
import cn.petshop.mall.entity.Sku;
import cn.petshop.mall.mapper.GoodsMapper;
import cn.petshop.mall.mapper.GoodsOrderMapper;
import cn.petshop.mall.mapper.InventoryLogMapper;
import cn.petshop.mall.mapper.OrderItemMapper;
import cn.petshop.mall.mapper.SkuMapper;
import cn.petshop.wallet.service.BalanceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品订单：下单、支付、取消、核销，以及退款时业务侧的置位与库存回补。
 *
 * <h3>价格不由客户端决定</h3>
 * 下单只接受 {@code skuId + qty}，金额一律按 SKU 现价在服务端算。客户端传价格字段也没用 ——
 * 与退款「金额不由客户端决定」是同一条原则。
 *
 * <h3>库存用 CAS 扣减，不做先查后改</h3>
 * {@code UPDATE sku SET stock = stock - qty WHERE id = ? AND stock >= qty} 由数据库行锁保证原子性，
 * 返回 0 即库存不足，整单失败回滚。若写成「先 SELECT 判断够不够，再 UPDATE」，
 * 两个并发下单会同时通过判断，超卖就此产生。
 *
 * <h3>状态迁移全部 CAS</h3>
 * 支付 / 取消 / 核销 / 退款都是 {@code WHERE status = 前置态} 的条件更新，
 * 并发下只有一个能成功。{@link GoodsOrderMapper#casRefund} 是"同一订单不会被退两次"的最终防线：
 * 它一旦返回 0，整个退款事务（含库存回补）都会回滚。
 */
@Service
@RequiredArgsConstructor
public class MallOrderService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final long MAX_PAGE_SIZE = 100;
    /** 单笔订单最大行数，防止一次请求拖垮库存与事务 */
    private static final int MAX_LINES = 20;
    /** 核销码撞唯一索引的重试次数 */
    private static final int VERIFY_CODE_RETRY = 5;
    /** 单轮超时关单的扫描上限，防止积压时一次扫描把库拖住 */
    private static final int MAX_EXPIRY_BATCH = 500;
    /** 系统自动关单的库存流水操作人 */
    private static final String SYSTEM_OPERATOR = "SYSTEM";

    private final GoodsOrderMapper orderMapper;
    private final OrderItemMapper itemMapper;
    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;
    private final InventoryLogMapper inventoryLogMapper;
    private final BalanceService balanceService;

    // ==================== 入参 ====================

    /** 下单：只给规格与数量，价格由服务端算。 */
    @Data
    public static class CreateCmd {
        private List<Item> items;

        @Data
        public static class Item {
            private Long skuId;
            private Integer qty;
        }
    }

    @Data
    public static class PayCmd {
        /** 当前仅支持 BALANCE，见 {@code pay} 的显式拒绝 */
        private String payType;
    }

    // ==================== 会员侧 ====================

    /**
     * 下单：校验 → 服务端定价 → CAS 锁库存 → 落单。
     * 下单即锁库存（状态 CREATED），取消或超时回补；这样不会出现"下单成功、付款时才发现没货"。
     */
    @Transactional
    public OrderVO create(Long memberId, Long storeId, CreateCmd cmd) {
        if (cmd == null || cmd.getItems() == null || cmd.getItems().isEmpty()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请选择要购买的商品");
        }
        if (cmd.getItems().size() > MAX_LINES) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "单笔订单最多 " + MAX_LINES + " 行");
        }

        // 同一 SKU 出现多次要合并数量 —— 拆成两行时每一行单独看都可能"库存够"，
        // 合并后才是真实需求，交给库存 CAS 一次性裁决。
        Map<Long, Integer> qtyBySku = new LinkedHashMap<>();
        for (CreateCmd.Item it : cmd.getItems()) {
            if (it == null || it.getSkuId() == null) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "商品规格不能为空");
            }
            if (it.getQty() == null || it.getQty() <= 0) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "购买数量必须大于 0");
            }
            qtyBySku.merge(it.getSkuId(), it.getQty(), Integer::sum);
        }

        BigDecimal total = ZERO;
        List<OrderItem> lines = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : qtyBySku.entrySet()) {
            Sku sku = skuMapper.selectById(e.getKey());
            if (sku == null) {
                throw new BizException(ErrorCode.BIZ_NOT_FOUND, "商品规格不存在");
            }
            Goods goods = goodsMapper.selectById(sku.getGoodsId());
            if (goods == null || !Goods.STATUS_ON_SALE.equals(goods.getStatus())) {
                throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "「" + sku.getName() + "」已下架");
            }
            int qty = e.getValue();
            // 价格取 SKU 现价，忽略客户端传来的任何金额
            total = total.add(sku.getPrice().multiply(BigDecimal.valueOf(qty)));

            OrderItem oi = new OrderItem();
            oi.setSkuId(sku.getId());
            // 落商品名快照：商品改名后老订单仍显示下单时的名字
            oi.setName(sku.getName());
            oi.setQty(qty);
            oi.setPrice(sku.getPrice());
            lines.add(oi);
        }
        if (total.compareTo(ZERO) <= 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "订单金额非法");
        }

        LocalDateTime now = LocalDateTime.now();
        String orderNo = NoUtil.orderNo();
        for (OrderItem oi : lines) {
            if (skuMapper.deductStock(oi.getSkuId(), oi.getQty()) == 0) {
                // 抛异常即整事务回滚，前面几行已扣的库存也会一并回滚
                throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "「" + oi.getName() + "」库存不足");
            }
            writeInventoryLog(oi.getSkuId(), InventoryLog.BIZ_SALE, -oi.getQty(), orderNo, null, now);
        }

        GoodsOrder order = new GoodsOrder();
        order.setOrderNo(orderNo);
        order.setStoreId(storeId);
        order.setMemberId(memberId);
        order.setTotal(total);
        order.setPrincipalPaid(ZERO);
        order.setGiftPaid(ZERO);
        order.setPayType(GoodsOrder.PAY_BALANCE);
        order.setStatus(GoodsOrder.STATUS_CREATED);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        insertWithUniqueVerifyCode(order);

        for (OrderItem oi : lines) {
            oi.setOrderId(order.getId());
            itemMapper.insert(oi);
        }
        return toVO(order, lines);
    }

    /**
     * 余额支付：扣款与状态迁移同一事务。
     *
     * <p>顺序是「快速失败检查 → 扣款拿拆分 → CAS 置为 PAID」。拆分结果直接取自账本口径
     * （{@link BalanceService#consumeAvailableWithSplit}），所以订单的
     * {@code principal_paid + gift_paid} 与消费流水严格相等，退款按原比例退回时不会对不上。
     */
    @Transactional
    public OrderVO pay(Long memberId, String orderNo, PayCmd cmd) {
        GoodsOrder order = requireOwnOrder(memberId, orderNo);
        String payType = (cmd == null || cmd.getPayType() == null || cmd.getPayType().isBlank())
                ? GoodsOrder.PAY_BALANCE
                : cmd.getPayType().trim().toUpperCase();
        if (!GoodsOrder.PAY_BALANCE.equals(payType)) {
            // 微信支付需要商户号与回调验签，当前环境不具备 —— 显式拒绝，不假装支持
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "当前仅支持余额支付，微信支付尚未开通");
        }
        if (!GoodsOrder.STATUS_CREATED.equals(order.getStatus())) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "订单当前状态不可支付，请刷新");
        }

        LocalDateTime now = LocalDateTime.now();
        // 余额是否充足由数据库条件更新裁决（deductAvailable 带 balance >= amt）
        BigDecimal[] split = balanceService.consumeAvailableWithSplit(memberId, order.getTotal(), orderNo);
        if (orderMapper.casPaid(orderNo, memberId, split[0], split[1], GoodsOrder.PAY_BALANCE, now) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "订单已支付或已取消，请刷新");
        }

        order.setStatus(GoodsOrder.STATUS_PAID);
        order.setPrincipalPaid(split[0]);
        order.setGiftPaid(split[1]);
        order.setPayType(GoodsOrder.PAY_BALANCE);
        order.setUpdatedAt(now);
        return toVO(order, loadItems(order.getId()));
    }

    /** 取消待支付订单并回补库存。已支付的单不能走这里，必须走退款审核。 */
    @Transactional
    public OrderVO cancel(Long memberId, String orderNo) {
        GoodsOrder order = requireOwnOrder(memberId, orderNo);
        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.casCancel(orderNo, memberId, now) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "只有待支付订单可以取消，已支付的请申请退款");
        }
        restoreStock(order, orderNo, null, now);
        order.setStatus(GoodsOrder.STATUS_CANCELLED);
        order.setUpdatedAt(now);
        return toVO(order, loadItems(order.getId()));
    }

    /** 我的订单列表（含明细），按时间倒序。 */
    public List<OrderVO> listMine(Long memberId) {
        return attachItems(orderMapper.selectList(new LambdaQueryWrapper<GoodsOrder>()
                .eq(GoodsOrder::getMemberId, memberId)
                .orderByDesc(GoodsOrder::getId)));
    }

    /** 订单详情（限本人）。 */
    public OrderVO detail(Long memberId, String orderNo) {
        GoodsOrder order = requireOwnOrder(memberId, orderNo);
        return toVO(order, loadItems(order.getId()));
    }

    // ==================== 后台侧 ====================

    /**
     * 核销（自提）：按核销码把 PAID 置为 VERIFIED。
     * 先做校验位预检（{@link VerifyCodeUtil#validate}）快速挡掉乱输，再交给唯一索引与 CAS。
     */
    @Transactional
    public OrderVO verifyByCode(String code) {
        if (code == null || code.isBlank() || !VerifyCodeUtil.validate(code.trim())) {
            throw new BizException(ErrorCode.BIZ_VERIFY_CODE_INVALID);
        }
        String verifyCode = code.trim();
        GoodsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<GoodsOrder>()
                .eq(GoodsOrder::getVerifyCode, verifyCode));
        if (order == null) {
            throw new BizException(ErrorCode.BIZ_VERIFY_CODE_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.casVerifyByCode(verifyCode, now) == 0) {
            // 走到这里说明码本身是有效的（订单查得到），只是当前状态不允许核销。
            // 原来一律提示"已核销或尚未支付"，已取消 / 已退款的单会被读成"还没付钱"，
            // 运营照着这个提示去查支付记录，越查越远（对应审查 P3-7）。现在按真实状态说。
            throw new BizException(ErrorCode.BIZ_VERIFY_CODE_INVALID, verifyBlockReason(order.getStatus()));
        }
        order.setStatus(GoodsOrder.STATUS_VERIFIED);
        order.setUpdatedAt(now);
        return toVO(order, loadItems(order.getId()));
    }

    /** 把「为什么这单不能核销」说清楚，供核销台直接显示。 */
    private static String verifyBlockReason(String status) {
        if (GoodsOrder.STATUS_VERIFIED.equals(status)) {
            return "该订单已经核销过了，不能重复核销";
        }
        if (GoodsOrder.STATUS_CANCELLED.equals(status)) {
            return "该订单已取消（超过支付时限未付款），不能核销";
        }
        if (GoodsOrder.STATUS_REFUNDED.equals(status)) {
            return "该订单已退款，不能核销";
        }
        if (GoodsOrder.STATUS_CREATED.equals(status)) {
            return "该订单尚未支付，请顾客先完成支付再核销";
        }
        return "该订单当前状态不可核销（" + status + "）";
    }

    public IPage<OrderVO> listForAdmin(Long storeId, String status, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<GoodsOrder> q = new LambdaQueryWrapper<GoodsOrder>()
                .eq(GoodsOrder::getStoreId, storeId)
                .orderByDesc(GoodsOrder::getId);
        if (status != null && !status.isBlank()) {
            q.eq(GoodsOrder::getStatus, status.trim().toUpperCase());
        }
        IPage<GoodsOrder> raw = orderMapper.selectPage(new Page<>(safePage, safeSize), q);
        List<OrderVO> vos = attachItems(raw.getRecords());
        Page<OrderVO> out = new Page<>(safePage, safeSize, raw.getTotal());
        out.setRecords(vos);
        return out;
    }

    // ==================== 超时关单（系统侧） ====================

    /**
     * 扫出超时未支付的订单号。
     *
     * <p>只读、不产生任何副作用，真正的判定与状态迁移在 {@link #closeExpiredOrder} 里由 CAS 复核。
     * 这里刻意<b>不</b>加事务：扫描跨越几百行数据，没必要为此占着一个连接开着事务。
     *
     * <p>每次最多取 {@code limit} 条并限定上限，保证单次扫描的工作量与耗时可控 ——
     * 积压很多时靠下一次扫描继续消化，而不是在一个循环里无限拖下去。
     * 按 {@code id} 升序取，让最老的订单优先被处理。
     */
    public List<String> findExpiredOrderNos(LocalDateTime deadline, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_EXPIRY_BATCH);
        return orderMapper.selectList(new LambdaQueryWrapper<GoodsOrder>()
                        .select(GoodsOrder::getOrderNo)
                        .eq(GoodsOrder::getStatus, GoodsOrder.STATUS_CREATED)
                        .lt(GoodsOrder::getCreatedAt, deadline)
                        .orderByAsc(GoodsOrder::getId)
                        .last("LIMIT " + safeLimit))
                .stream()
                .map(GoodsOrder::getOrderNo)
                .toList();
    }

    /**
     * 关闭单张超时订单并回补库存。关掉了返回 {@code true}。
     *
     * <p><b>为什么这个方法自己带事务，而不是让扫描循环共用一个</b>：
     * 一个批次里只要有一单因为意料外的原因抛异常，批级事务会把整批一起回滚 ——
     * 前面几十单白白白做，而且如果是那一单数据本身有问题，就会每轮扫描都在同一个地方翻车、
     * 永远推进不下去。拆成「一单一个事务」后，坏的那单单独失败被记日志，其余的照常推进。
     * 这个拆分能生效的前提是<b>调用方在另一个 Bean 上</b>（{@code OrderExpiryJob}），
     * 而不是本类内部自调用 —— 自调用不走代理，{@code @Transactional} 会静默失效。
     *
     * <p>{@code deadline} 由调用方在扫描时算好并一路传下来，好让 CAS 里的超时判定
     * 与扫描用的判定是同一个时刻，不会因为两处各自 {@code now()} 而口径不一。
     */
    @Transactional
    public boolean closeExpiredOrder(String orderNo, LocalDateTime deadline) {
        LocalDateTime now = LocalDateTime.now();
        // CAS 返回 0 说明这张单不该被关：要么顾客刚付款，要么已被自己取消，要么时间其实还没到。
        // 三种情况都不需要回补库存，直接跳过（不回补正是"没有重复回补"的保证）。
        if (orderMapper.casExpire(orderNo, deadline, now) == 0) {
            return false;
        }
        GoodsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<GoodsOrder>()
                .eq(GoodsOrder::getOrderNo, orderNo));
        // 记成 SYSTEM 而不是空值：库存流水要能回答"这批货是谁放回去的"
        restoreStock(order, orderNo, SYSTEM_OPERATOR, now);
        return true;
    }

    // ==================== 供退款链路调用 ====================

    /**
     * 取本人订单。越权与不存在返回同一错误码，避免被用来探测他人订单号是否存在。
     * 供退款服务解析来源单据（{@code RefundService} 的 GOODS 分支）。
     */
    public GoodsOrder requireOwnOrder(Long memberId, String orderNo) {
        GoodsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<GoodsOrder>()
                .eq(GoodsOrder::getOrderNo, orderNo));
        if (order == null || !order.getMemberId().equals(memberId)) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "订单不存在");
        }
        return order;
    }

    /**
     * 退款审核通过后业务侧置位：订单 {@code PAID → REFUNDED} 并回补库存。
     *
     * <p>与账务的入余额动作由 {@code RefundService} 放在<b>同一事务</b>里调用，
     * 任一步失败整体回滚 —— 不会出现「钱退了但库存没回来」或反之。
     * CAS 返回 0（订单已核销 / 已退款）时直接抛异常阻断入账。
     */
    @Transactional
    public void markRefunded(Long memberId, String orderNo, String refundNo, String operator) {
        GoodsOrder order = requireOwnOrder(memberId, orderNo);
        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.casRefund(orderNo, memberId, now) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该订单已核销或已退款，无法退款");
        }
        restoreStock(order, refundNo, operator, now);
    }

    // ==================== 内部 ====================

    /**
     * 生成核销码并落单。核销码有唯一索引，撞码时重试若干次 ——
     * 直接让请求 500 太粗暴，且这类碰撞在 8 位定长码下并非不可能。
     */
    private void insertWithUniqueVerifyCode(GoodsOrder order) {
        for (int i = 0; i < VERIFY_CODE_RETRY; i++) {
            order.setVerifyCode(VerifyCodeUtil.generate());
            try {
                orderMapper.insert(order);
                return;
            } catch (DuplicateKeyException e) {
                // 核销码撞唯一索引，换个码重试
            }
        }
        throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "核销码生成失败，请重试");
    }

    /** 回补订单全部行的库存，并写库存流水。 */
    private void restoreStock(GoodsOrder order, String bizNo, String operator, LocalDateTime now) {
        if (order == null) {
            return;
        }
        for (OrderItem oi : loadItems(order.getId())) {
            skuMapper.restoreStock(oi.getSkuId(), oi.getQty());
            writeInventoryLog(oi.getSkuId(), InventoryLog.BIZ_RESTORE, oi.getQty(), bizNo, operator, now);
        }
    }

    private void writeInventoryLog(Long skuId, String bizType, int qty, String bizNo,
                                   String operator, LocalDateTime now) {
        Sku after = skuMapper.selectById(skuId);
        InventoryLog log = new InventoryLog();
        log.setSkuId(skuId);
        log.setBizType(bizType);
        log.setQty(qty);
        log.setStockAfter(after == null ? 0 : after.getStock());
        log.setBizNo(bizNo);
        log.setOperator(operator);
        log.setCreatedAt(now);
        inventoryLogMapper.insert(log);
    }

    private List<OrderItem> loadItems(Long orderId) {
        return itemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId)
                .orderByAsc(OrderItem::getId));
    }

    /** 批量装配明细，避免列表页 N+1 查询。 */
    private List<OrderVO> attachItems(List<GoodsOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<Long> ids = orders.stream().map(GoodsOrder::getId).toList();
        Map<Long, List<OrderItem>> byOrder = itemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderId, ids)
                        .orderByAsc(OrderItem::getId))
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return orders.stream()
                .map(o -> toVO(o, byOrder.getOrDefault(o.getId(), List.of())))
                .toList();
    }

    private OrderVO toVO(GoodsOrder order, List<OrderItem> items) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTotal(order.getTotal());
        vo.setPrincipalPaid(order.getPrincipalPaid());
        vo.setGiftPaid(order.getGiftPaid());
        vo.setPayType(order.getPayType());
        vo.setStatus(order.getStatus());
        // 终态后不再展示核销码：已核销的码没有意义，已取消/已退款的单更不该还能被扫
        vo.setVerifyCode(GoodsOrder.STATUS_CREATED.equals(order.getStatus())
                || GoodsOrder.STATUS_PAID.equals(order.getStatus()) ? order.getVerifyCode() : null);
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());
        vo.setRefundable(GoodsOrder.isRefundable(order));
        vo.setItems(items.stream().map(i -> {
            OrderVO.ItemVO iv = new OrderVO.ItemVO();
            iv.setSkuId(i.getSkuId());
            iv.setName(i.getName());
            iv.setQty(i.getQty());
            iv.setPrice(i.getPrice());
            return iv;
        }).toList());
        return vo;
    }
}
