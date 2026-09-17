package cn.petshop.mall.service;

import cn.petshop.common.audit.ConfigAuditLog;
import cn.petshop.common.audit.ConfigAuditService;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.mall.entity.Goods;
import cn.petshop.mall.entity.InventoryLog;
import cn.petshop.mall.entity.Sku;
import cn.petshop.mall.mapper.GoodsMapper;
import cn.petshop.mall.mapper.InventoryLogMapper;
import cn.petshop.mall.mapper.SkuMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台商品与库存配置。
 *
 * <h3>为什么后台「不提供删除」</h3>
 * {@code order_item} 里存了 sku 主键与成交价快照，但后台报表、售后追溯仍然要能顺着
 * {@code sku_id} 找回"当时卖的是哪个规格"。硬删一个 SKU 会让历史订单里那行变成孤儿。
 * 所以下架（商品级）与改库存（SKU 级）就够了 —— 停售是运营动作，删除是数据事故。
 *
 * <h3>priceFrom 由 SKU 推导，不接受客户端传入</h3>
 * 列表页按"X 元起"排序、会员端按它展示，一旦能被单独写入，就会出现
 * 起价 99 但最低 SKU 是 199 的展示事故。这里统一在 SKU 增改后重算。
 */
@Service
@RequiredArgsConstructor
public class AdminCatalogService {

    /** 单商品 SKU 数量上限：后台手滑粘贴几百行不该拖垮门店端列表接口 */
    private static final int MAX_SKUS_PER_GOODS = 50;

    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;
    private final InventoryLogMapper inventoryLogMapper;
    private final ConfigAuditService auditService;

    // ------------------------------------------------------------------ 商品

    public IPage<AdminGoodsVO> listGoods(Long storeId, String keyword, String status, long page, long size) {
        LambdaQueryWrapper<Goods> q = new LambdaQueryWrapper<Goods>()
                .eq(Goods::getStoreId, storeId)
                .orderByDesc(Goods::getId);
        if (keyword != null && !keyword.isBlank()) {
            q.like(Goods::getName, keyword.trim());
        }
        if (status != null && !status.isBlank()) {
            q.eq(Goods::getStatus, status.trim());
        }
        Page<Goods> p = goodsMapper.selectPage(new Page<>(page, capSize(size)), q);

        List<Long> goodsIds = p.getRecords().stream().map(Goods::getId).toList();
        Map<Long, List<Sku>> skuByGoods = batchLoadSkus(goodsIds);

        return p.convert(g -> toVO(g, skuByGoods.getOrDefault(g.getId(), List.of())));
    }

    public AdminGoodsVO goodsDetail(Long goodsId) {
        Goods goods = requireGoods(goodsId);
        return toVO(goods, loadSkus(goodsId));
    }

    @Data
    public static class GoodsCmd {
        private String name;
        private String category;
        /** 逗号分隔或 JSON 数组，与 MallService 的解析口径一致 */
        private String images;
        private String description;
        /** 新建时可携带初始 SKU；为空则建出一个"待补规格"的商品（不可上架） */
        private List<SkuCmd> skus;
        /** 兼容标签，当前无独立经营场景，留空即可 */
        private String operatorNote;
    }

    @Data
    public static class SkuCmd {
        private String name;
        private BigDecimal price;
        private Integer stock;
    }

    @Transactional
    public AdminGoodsVO createGoods(Long storeId, GoodsCmd cmd, String operator) {
        if (cmd == null || cmd.getName() == null || cmd.getName().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "商品名称不能为空");
        }
        Goods goods = new Goods();
        goods.setStoreId(storeId);
        goods.setName(cmd.getName().trim());
        goods.setCategory(trimToNull(cmd.getCategory()));
        goods.setImages(trimToNull(cmd.getImages()));
        goods.setDescription(trimToNull(cmd.getDescription()));
        goods.setPriceFrom(null);
        // 新建的商品一律先落在架下：SKU 还没配全就上架，会员点进去会下不了单
        goods.setStatus(Goods.STATUS_OFF_SALE);
        goods.setCreatedAt(LocalDateTime.now());
        goods.setUpdatedAt(LocalDateTime.now());
        goodsMapper.insert(goods);

        if (cmd.getSkus() != null) {
            for (SkuCmd skuCmd : cmd.getSkus()) {
                insertSku(goods.getId(), skuCmd);
            }
        }
        recomputePriceFrom(goods);

        auditService.created(ConfigAuditLog.TARGET_GOODS, goods.getId(),
                "新建商品：" + goods.getName(), operator);
        return goodsDetail(goods.getId());
    }

    /**
     * 改基本信息。刻意<b>不含</b> status 与 priceFrom：
     * 一个"顺手也能改状态"的通用更新接口，最终一定会被前端在某处误调用成"保存时把下架商品改回在架"。
     */
    @Transactional
    public AdminGoodsVO updateGoods(Long goodsId, GoodsCmd cmd, String operator) {
        Goods goods = requireGoods(goodsId);
        if (cmd == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请求体不能为空");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("名称", goods.getName());
        before.put("分类", goods.getCategory());
        before.put("图片", goods.getImages());
        before.put("描述", goods.getDescription());

        if (cmd.getName() != null && !cmd.getName().isBlank()) {
            goods.setName(cmd.getName().trim());
        }
        if (cmd.getCategory() != null) {
            goods.setCategory(trimToNull(cmd.getCategory()));
        }
        if (cmd.getImages() != null) {
            goods.setImages(trimToNull(cmd.getImages()));
        }
        if (cmd.getDescription() != null) {
            goods.setDescription(trimToNull(cmd.getDescription()));
        }
        goods.setUpdatedAt(LocalDateTime.now());
        goodsMapper.updateById(goods);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("名称", goods.getName());
        after.put("分类", goods.getCategory());
        after.put("图片", goods.getImages());
        after.put("描述", goods.getDescription());
        auditService.diff(ConfigAuditLog.TARGET_GOODS, goodsId, before, after, null, operator);
        return goodsDetail(goodsId);
    }

    /**
     * 上下架。
     *
     * <p>上架前检查 SKU 齐备性：没有 SKU、或全部 SKU 都没定价的商品上架后，
     * 会员端会展示一个点不动的商品 —— 这类"看起来能用其实不能用"的状态，
     * 比直接拦下来更难排查。</p>
     */
    @Transactional
    public AdminGoodsVO setGoodsStatus(Long goodsId, String status, String operator) {
        Goods goods = requireGoods(goodsId);
        String target = status == null ? "" : status.trim().toUpperCase();
        if (!Goods.STATUS_ON_SALE.equals(target) && !Goods.STATUS_OFF_SALE.equals(target)) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "状态只能是 ON_SALE 或 OFF_SALE");
        }
        if (target.equals(goods.getStatus())) {
            // 幂等：重复点"上架"不是错误，但不必落一条空审计
            return goodsDetail(goodsId);
        }
        if (Goods.STATUS_ON_SALE.equals(target)) {
            List<Sku> skus = loadSkus(goodsId);
            if (skus.isEmpty()) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "该商品还没有规格，无法上架");
            }
            boolean anyPriced = skus.stream().anyMatch(s -> s.getPrice() != null
                    && s.getPrice().compareTo(BigDecimal.ZERO) > 0);
            if (!anyPriced) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "所有规格的价格都为空或为 0，无法上架");
            }
        }

        String before = goods.getStatus();
        goods.setStatus(target);
        goods.setUpdatedAt(LocalDateTime.now());
        goodsMapper.updateById(goods);
        auditService.single(ConfigAuditLog.TARGET_GOODS, goodsId, ConfigAuditLog.ACTION_STATUS,
                "状态", before, target, null, operator);
        return goodsDetail(goodsId);
    }

    // -------------------------------------------------------------------- SKU

    @Transactional
    public AdminGoodsVO addSku(Long goodsId, SkuCmd cmd, String operator) {
        requireGoods(goodsId);
        insertSku(goodsId, cmd);
        recomputePriceFrom(requireGoods(goodsId));
        return goodsDetail(goodsId);
    }

    /**
     * 改 SKU 的名称与价格。
     *
     * <p>容器里 {@code order_item.price} 存的是成交快照，因此改价只影响之后的新订单，
     * 已下单的客户不受影响 —— 这也是允许后台直接改价（而不需要"新建 SKU 再停用旧 SKU"）的前提。</p>
     */
    @Transactional
    public AdminGoodsVO updateSku(Long skuId, SkuCmd cmd, String operator) {
        Sku sku = requireSku(skuId);
        if (cmd == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请求体不能为空");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("规格名", sku.getName());
        before.put("价格", sku.getPrice());

        if (cmd.getName() != null && !cmd.getName().isBlank()) {
            sku.setName(cmd.getName().trim());
        }
        if (cmd.getPrice() != null) {
            if (cmd.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                // 0 元 SKU 会被当成"赠品"绕过收银，而这里是商品目录，不是营销工具
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "价格必须大于 0");
            }
            sku.setPrice(cmd.getPrice());
        }
        skuMapper.updateById(sku);
        recomputePriceFrom(requireGoods(sku.getGoodsId()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("规格名", sku.getName());
        after.put("价格", sku.getPrice());
        auditService.diff(ConfigAuditLog.TARGET_SKU, skuId, before, after,
                "商品#" + sku.getGoodsId(), operator);
        return goodsDetail(sku.getGoodsId());
    }

    /**
     * 人工盘点库存：{@code delta} 可正可负。
     *
     * <p>两件事必须做，缺一不可：</p>
     * <ol>
     *   <li><b>原因必填</b>。库存调整没有对应单据，{@code biz_no} 为空，
     *       这是"货少了但不知道去哪了"唯一能查的线索。</li>
     *   <li><b>防下溢走 SQL 条件</b>。{@code SkuMapper.adjustStock} 把
     *       {@code stock + delta >= 0} 写在 WHERE 里；先查再判断会让两个店员
     *       同时减库存时双双通过检查，把库存减成负数。</li>
     * </ol>
     */
    @Transactional
    public Map<String, Object> adjustStock(Long skuId, int delta, String remark, String operator) {
        Sku sku = requireSku(skuId);
        if (delta == 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "调整数量不能为 0");
        }
        if (remark == null || remark.isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请填写盘点原因（库存调整没有单据可查，原因必须落库）");
        }
        int before = sku.getStock() == null ? 0 : sku.getStock();

        int updated = skuMapper.adjustStock(skuId, delta);
        if (updated == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT,
                    "调整后库存会变成负数（当前 " + before + "，本次 " + delta + "），请核对实物数量");
        }

        // 重新读一次拿到数据库里的权威结果，避免用「本地算出来的值」写流水
        Sku after = skuMapper.selectById(skuId);
        int stockAfter = after.getStock() == null ? 0 : after.getStock();

        InventoryLog log = new InventoryLog();
        log.setSkuId(skuId);
        log.setBizType(InventoryLog.BIZ_ADJUST);
        log.setQty(delta);
        log.setStockAfter(stockAfter);
        // 人工盘点没有外部单据号，用备注自身承载原因，不再伪造一个 biz_no
        log.setBizNo(null);
        log.setOperator(operator);
        log.setCreatedAt(LocalDateTime.now());
        inventoryLogMapper.insert(log);

        auditService.single(ConfigAuditLog.TARGET_SKU, skuId, ConfigAuditLog.ACTION_ADJUST,
                "库存", String.valueOf(before), String.valueOf(stockAfter), remark, operator);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skuId", skuId);
        result.put("stockBefore", before);
        result.put("stockAfter", stockAfter);
        result.put("delta", delta);
        return result;
    }

    /** 某 SKU 的库存变动历史（含销售出库与盘点）。 */
    public List<InventoryLog> inventoryLogs(Long skuId, long limit) {
        return new ArrayList<>(inventoryLogMapper.selectList(new LambdaQueryWrapper<InventoryLog>()
                .eq(InventoryLog::getSkuId, skuId)
                .orderByDesc(InventoryLog::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200))));
    }

    // ----------------------------------------------------------------- 内部

    private void insertSku(Long goodsId, SkuCmd cmd) {
        if (cmd == null || cmd.getName() == null || cmd.getName().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "规格名称不能为空");
        }
        if (cmd.getPrice() == null || cmd.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "规格价格必须大于 0");
        }
        int stock = cmd.getStock() == null ? 0 : cmd.getStock();
        if (stock < 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "初始库存不能为负");
        }
        if (loadSkus(goodsId).size() >= MAX_SKUS_PER_GOODS) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                    "单个商品最多 " + MAX_SKUS_PER_GOODS + " 个规格，请拆成多个商品");
        }
        Sku sku = new Sku();
        sku.setGoodsId(goodsId);
        sku.setName(cmd.getName().trim());
        sku.setPrice(cmd.getPrice());
        sku.setStock(stock);
        skuMapper.insert(sku);
    }

    /**
     * 重算 {@code price_from}。无 SKU 或全无价时写回 null，
     * 而不是留一个过期的旧起价 —— 列表页展示"199 元起"但商品已无规格是最容易被投诉的那类错。
     */
    private void recomputePriceFrom(Goods goods) {
        BigDecimal min = loadSkus(goods.getId()).stream()
                .map(Sku::getPrice)
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .min(BigDecimal::compareTo)
                .orElse(null);
        if (java.util.Objects.equals(min, goods.getPriceFrom())) {
            return;
        }
        goods.setPriceFrom(min);
        goodsMapper.updateById(goods);
    }

    private Goods requireGoods(Long goodsId) {
        if (goodsId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "商品 ID 不能为空");
        }
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "商品不存在");
        }
        return goods;
    }

    private Sku requireSku(Long skuId) {
        if (skuId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "规格 ID 不能为空");
        }
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "规格不存在");
        }
        return sku;
    }

    private List<Sku> loadSkus(Long goodsId) {
        return skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getGoodsId, goodsId)
                .orderByAsc(Sku::getId));
    }

    /** 一次查全部 SKU 再分组，避免列表页 N+1。 */
    private Map<Long, List<Sku>> batchLoadSkus(List<Long> goodsIds) {
        if (goodsIds == null || goodsIds.isEmpty()) {
            return Map.of();
        }
        return skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                        .in(Sku::getGoodsId, goodsIds)
                        .orderByAsc(Sku::getId))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(Sku::getGoodsId));
    }

    private AdminGoodsVO toVO(Goods goods, List<Sku> skus) {
        AdminGoodsVO vo = new AdminGoodsVO();
        vo.setId(goods.getId());
        vo.setName(goods.getName());
        vo.setCategory(goods.getCategory());
        vo.setImages(splitImages(goods.getImages()));
        vo.setDescription(goods.getDescription());
        vo.setPriceFrom(goods.getPriceFrom());
        vo.setStatus(goods.getStatus());
        vo.setTotalStock(skus.stream()
                .map(s -> s.getStock() == null ? 0 : s.getStock())
                .reduce(0, Integer::sum));
        vo.setSkus(skus.stream().map(s -> {
            AdminGoodsVO.SkuVO sv = new AdminGoodsVO.SkuVO();
            sv.setId(s.getId());
            sv.setName(s.getName());
            sv.setPrice(s.getPrice());
            sv.setStock(s.getStock());
            return sv;
        }).toList());
        return vo;
    }

    /** 与 {@code MallService.parseImages} 同一口径：兼容 JSON 数组与逗号分隔。 */
    private List<String> splitImages(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String v = part.trim().replaceAll("^\"|\"$", "");
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    /** 分页上限 100，与项目其余后台列表口径一致。 */
    private long capSize(long size) {
        return size <= 0 ? 20 : Math.min(size, 100);
    }
}
