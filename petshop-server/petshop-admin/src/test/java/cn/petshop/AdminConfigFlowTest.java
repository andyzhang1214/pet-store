package cn.petshop;

import cn.petshop.booking.entity.ServiceItem;
import cn.petshop.booking.entity.Spec;
import cn.petshop.booking.entity.StoreConfig;
import cn.petshop.booking.service.AdminPricingService;
import cn.petshop.common.audit.ConfigAuditLog;
import cn.petshop.common.audit.ConfigAuditService;
import cn.petshop.common.web.BizException;
import cn.petshop.mall.entity.InventoryLog;
import cn.petshop.mall.service.AdminCatalogService;
import cn.petshop.mall.service.AdminGoodsVO;
import cn.petshop.member.entity.MemberLevel;
import cn.petshop.member.service.AdminMemberLevelService;
import cn.petshop.wallet.entity.Card;
import cn.petshop.wallet.entity.CardType;
import cn.petshop.wallet.entity.RechargeTier;
import cn.petshop.wallet.mapper.CardMapper;
import cn.petshop.wallet.mapper.CardTypeMapper;
import cn.petshop.wallet.service.AdminCardTypeService;
import cn.petshop.wallet.service.RechargeTierService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置域后台：商品 / SKU / 库存 / 服务 / 价目 / 卡类型 / 充值档位 / 会员等级 / 门店规则
 * 的业务规则与留痕回归。
 *
 * <h3>这个测试类主要在守什么</h3>
 * 配置域的接口大多是"能改就改"，本身不容易出错；真正会出事的是<b>那几条不许改的边界</b>：
 * 库存不许减成负数、SKU 次数不许低于已用次数、门槛不许倒挂、赠送额不许由客户端决定。
 * 这些规则一旦被后来的改动抹平，不会有任何报错，只会静默地放行 ——
 * 所以每条都配一个负向用例。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminConfigFlowTest {

    private static final Long STORE = 1L;
    private static final String OP = "店长测试";

    @Autowired
    private AdminCatalogService catalogService;
    @Autowired
    private AdminPricingService pricingService;
    @Autowired
    private AdminCardTypeService cardTypeService;
    @Autowired
    private RechargeTierService rechargeTierService;
    @Autowired
    private AdminMemberLevelService memberLevelService;
    @Autowired
    private ConfigAuditService auditService;
    @Autowired
    private CardMapper cardMapper;
    @Autowired
    private CardTypeMapper cardTypeMapper;

    private AdminCatalogService.GoodsCmd goodsCmd(String name, String... skuPrices) {
        AdminCatalogService.GoodsCmd cmd = new AdminCatalogService.GoodsCmd();
        cmd.setName(name);
        cmd.setCategory("SNACK");
        java.util.List<AdminCatalogService.SkuCmd> skus = new java.util.ArrayList<>();
        int i = 1;
        for (String price : skuPrices) {
            AdminCatalogService.SkuCmd s = new AdminCatalogService.SkuCmd();
            s.setName("规格" + i++);
            s.setPrice(new BigDecimal(price));
            s.setStock(10);
            skus.add(s);
        }
        cmd.setSkus(skus);
        return cmd;
    }

    // ============================================================ 商品与 SKU

    @Test
    void newGoodsStartsOffSaleAndCannotGoOnSaleWithoutSkus() {
        AdminCatalogService.GoodsCmd empty = new AdminCatalogService.GoodsCmd();
        empty.setName("空规格商品-" + System.nanoTime());
        AdminGoodsVO created = catalogService.createGoods(STORE, empty, OP);

        assertEquals("OFF_SALE", created.getStatus(), "新建商品必须先落在架下，避免规格没配全就能被下单");
        assertTrue(created.getSkus().isEmpty());
        assertNull(created.getPriceFrom());

        BizException e = assertThrows(BizException.class,
                () -> catalogService.setGoodsStatus(created.getId(), "ON_SALE", OP));
        assertTrue(e.getMessage().contains("还没有规格"), "报错要说清原因，而不是笼统的参数不合法");
    }

    @Test
    void priceFromIsDerivedFromCheapestSku() {
        AdminGoodsVO goods = catalogService.createGoods(STORE,
                goodsCmd("起价推导-" + System.nanoTime(), "120.00", "88.00", "99.00"), OP);

        assertEquals(0, new BigDecimal("88.00").compareTo(goods.getPriceFrom()),
                "起价必须取最低 SKU 价；允许客户端单独写 price_from 会造出「起价 88 实际最低 120」的展示事故");

        // 再加一个更便宜的 SKU，起价要跟着动
        AdminCatalogService.SkuCmd cheaper = new AdminCatalogService.SkuCmd();
        cheaper.setName("特价规格");
        cheaper.setPrice(new BigDecimal("66.00"));
        cheaper.setStock(3);
        AdminGoodsVO after = catalogService.addSku(goods.getId(), cheaper, OP);
        assertEquals(0, new BigDecimal("66.00").compareTo(after.getPriceFrom()));

        // 库存合计也要跟着算
        assertEquals(33, after.getTotalStock(), "10*3 + 3");
    }

    @Test
    void zeroOrNegativeSkuPriceIsRejected() {
        AdminGoodsVO goods = catalogService.createGoods(STORE,
                goodsCmd("零价拦截-" + System.nanoTime(), "50.00"), OP);

        AdminCatalogService.SkuCmd free = new AdminCatalogService.SkuCmd();
        free.setName("赠品规格");
        free.setPrice(BigDecimal.ZERO);
        // 0 元 SKU 会被当成"赠品"绕过收银，商品目录不是营销工具
        assertThrows(BizException.class, () -> catalogService.addSku(goods.getId(), free, OP));
    }

    @Test
    void skuPriceChangeIsAuditedWithBeforeAndAfter() {
        AdminGoodsVO goods = catalogService.createGoods(STORE,
                goodsCmd("改价留痕-" + System.nanoTime(), "150.00"), OP);
        Long skuId = goods.getSkus().get(0).getId();

        AdminCatalogService.SkuCmd patch = new AdminCatalogService.SkuCmd();
        patch.setPrice(new BigDecimal("180.00"));
        catalogService.updateSku(skuId, patch, OP);

        List<ConfigAuditLog> logs = auditService.list(ConfigAuditLog.TARGET_SKU, skuId, 20);
        assertFalse(logs.isEmpty(), "改价必须留下审计行");
        ConfigAuditLog row = logs.get(0);
        assertEquals("价格", row.getFieldName());
        assertEquals("150.00", row.getBeforeValue());
        assertEquals("180.00", row.getAfterValue());
        assertEquals(OP, row.getOperator(), "操作人必须落库，否则改价记录没有证据价值");
    }

    @Test
    void unchangedGoodsUpdateWritesNoAuditNoise() {
        AdminGoodsVO goods = catalogService.createGoods(STORE,
                goodsCmd("空改不留痕-" + System.nanoTime(), "60.00"), OP);

        AdminCatalogService.GoodsCmd same = new AdminCatalogService.GoodsCmd();
        same.setCategory("SNACK"); // 与创建时相同
        catalogService.updateGoods(goods.getId(), same, OP);

        List<ConfigAuditLog> logs = auditService.list(ConfigAuditLog.TARGET_GOODS, goods.getId(), 50);
        assertTrue(logs.stream().noneMatch(l -> ConfigAuditLog.ACTION_UPDATE.equals(l.getAction())),
                "什么都没改就不该产生审计行，否则真正的变更会被噪音淹掉");
    }

    /**
     * 后台"全部对象"视图走 recent() → list(null, null, limit)。
     * 回归背景：list() 曾无条件 .eq(targetType, null)，MP 生成
     * `WHERE target_type = NULL` 永远 0 行 —— 而所有按对象查的测试都传了
     * 具体类型，全绿照跑。这条测试专门守住"不限定对象"的路径。
     */
    @Test
    void recentReturnsRowsWithoutTargetTypeFilter() {
        catalogService.createGoods(STORE,
                goodsCmd("全局流-" + System.nanoTime(), "42.00"), OP);

        List<ConfigAuditLog> recent = auditService.recent(50);
        assertFalse(recent.isEmpty(), "不限定对象的全局审计流不能为空");
        // 倒序：最新的变更在最前
        for (int i = 1; i < recent.size(); i++) {
            assertTrue(recent.get(i - 1).getId() >= recent.get(i).getId(),
                    "recent 必须按 id 倒序返回");
        }
    }

    // ================================================================ 库存盘点

    @Test
    void stockAdjustWritesInventoryLogAndAudit() {
        AdminGoodsVO goods = catalogService.createGoods(STORE,
                goodsCmd("盘点-" + System.nanoTime(), "30.00"), OP);
        Long skuId = goods.getSkus().get(0).getId();

        Map<String, Object> result = catalogService.adjustStock(skuId, 5, "到货补录", OP);
        assertEquals(10, result.get("stockBefore"));
        assertEquals(15, result.get("stockAfter"));

        List<InventoryLog> logs = catalogService.inventoryLogs(skuId, 10);
        InventoryLog adjust = logs.stream()
                .filter(l -> InventoryLog.BIZ_ADJUST.equals(l.getBizType()))
                .findFirst().orElse(null);
        assertNotNull(adjust, "人工盘点必须落库存流水");
        assertEquals(5, adjust.getQty());
        assertEquals(15, adjust.getStockAfter());
        assertEquals(OP, adjust.getOperator());
        assertNull(adjust.getBizNo(), "人工盘点没有外部单据，不能伪造一个 biz_no");

        List<ConfigAuditLog> audits = auditService.list(ConfigAuditLog.TARGET_SKU, skuId, 10);
        ConfigAuditLog stockRow = audits.stream()
                .filter(l -> "库存".equals(l.getFieldName()))
                .findFirst().orElse(null);
        assertNotNull(stockRow);
        assertEquals("10", stockRow.getBeforeValue());
        assertEquals("15", stockRow.getAfterValue());
        assertEquals("到货补录", stockRow.getRemark(), "盘点原因必须落审计，这是货少了唯一能查的线索");
    }

    @Test
    void stockAdjustCannotDriveStockNegative() {
        AdminGoodsVO goods = catalogService.createGoods(STORE,
                goodsCmd("防下溢-" + System.nanoTime(), "30.00"), OP);
        Long skuId = goods.getSkus().get(0).getId();

        assertThrows(BizException.class, () -> catalogService.adjustStock(skuId, -11, "盘点少了", OP));

        AdminGoodsVO after = catalogService.goodsDetail(goods.getId());
        assertEquals(10, after.getSkus().get(0).getStock(), "被拒绝的盘点不得改动库存");
    }

    @Test
    void stockAdjustRequiresReason() {
        AdminGoodsVO goods = catalogService.createGoods(STORE,
                goodsCmd("原因必填-" + System.nanoTime(), "30.00"), OP);
        Long skuId = goods.getSkus().get(0).getId();

        assertThrows(BizException.class, () -> catalogService.adjustStock(skuId, 1, "  ", OP));
        assertThrows(BizException.class, () -> catalogService.adjustStock(skuId, 0, "调整 0", OP));
    }

    // ========================================================= 服务与价目

    @Test
    void newServiceIsDisabledUntilItHasPrices() {
        AdminPricingService.ServiceCmd cmd = new AdminPricingService.ServiceCmd();
        cmd.setName("测试服务-" + System.nanoTime());
        cmd.setCategory("WASH");
        cmd.setDurationSlots(2);
        cmd.setCapacity(2);
        ServiceItem item = pricingService.createService(STORE, cmd, OP);

        assertEquals("DISABLED", item.getStatus(), "新建服务先停用：价目没配就上架会挂出一个点进去报错的服务");

        BizException e = assertThrows(BizException.class,
                () -> pricingService.setServiceStatus(item.getId(), "ENABLED", OP));
        assertTrue(e.getMessage().contains("价格档"));

        // 配一格价之后即可启用
        Spec spec = pricingService.listSpecs(STORE).get(0);
        pricingService.setPrice(item.getId(), spec.getId(), new BigDecimal("138.00"), OP);
        ServiceItem enabled = pricingService.setServiceStatus(item.getId(), "ENABLED", OP);
        assertEquals("ENABLED", enabled.getStatus());
    }

    @Test
    void serviceCategoryMustBeKnown() {
        AdminPricingService.ServiceCmd cmd = new AdminPricingService.ServiceCmd();
        cmd.setName("乱分类");
        cmd.setCategory("HAIRCUT");
        assertThrows(BizException.class, () -> pricingService.createService(STORE, cmd, OP));
    }

    @Test
    void priceMatrixIncludesGaps() {
        AdminPricingService.ServiceCmd cmd = new AdminPricingService.ServiceCmd();
        cmd.setName("矩阵缺口-" + System.nanoTime());
        cmd.setCategory("GROOM");
        ServiceItem item = pricingService.createService(STORE, cmd, OP);

        List<AdminPricingService.PriceRow> rows = pricingService.priceMatrix(STORE, item.getId());
        assertFalse(rows.isEmpty(), "价目矩阵必须列出门店所有规格档");
        assertTrue(rows.stream().allMatch(AdminPricingService.PriceRow::isMissing),
                "一格价都没配时，每一行都应是空档（price=null），而不是被过滤掉 —— "
                        + "否则店长看到的是「每格都有价」的假象");

        Long specId = rows.get(0).getSpecId();        pricingService.setPrice(item.getId(), specId, new BigDecimal("199.00"), OP);

        List<AdminPricingService.PriceRow> after = pricingService.priceMatrix(STORE, item.getId());
        AdminPricingService.PriceRow filled = after.stream()
                .filter(r -> r.getSpecId().equals(specId)).findFirst().orElseThrow();
        assertFalse(filled.isMissing());
        assertEquals(0, new BigDecimal("199.00").compareTo(filled.getPrice()));
    }

    @Test
    void priceChangeIsAudited() {
        AdminPricingService.ServiceCmd cmd = new AdminPricingService.ServiceCmd();
        cmd.setName("改价留痕-" + System.nanoTime());
        cmd.setCategory("SPA");
        ServiceItem item = pricingService.createService(STORE, cmd, OP);
        Long specId = pricingService.listSpecs(STORE).get(0).getId();

        pricingService.setPrice(item.getId(), specId, new BigDecimal("200.00"), OP);
        pricingService.setPrice(item.getId(), specId, new BigDecimal("240.00"), OP);

        List<ConfigAuditLog> logs = auditService.list(ConfigAuditLog.TARGET_SERVICE_PRICE, specId, 20);
        ConfigAuditLog row = logs.stream().findFirst().orElse(null);
        assertNotNull(row);
        assertEquals("200.00", row.getBeforeValue());
        assertEquals("240.00", row.getAfterValue());
        assertEquals("价格", row.getFieldName());
    }

    @Test
    void storeConfigRejectsZeroCapacityAndBadHours() {
        AdminPricingService.StoreConfigCmd bad = new AdminPricingService.StoreConfigCmd();
        bad.setSlotCapacity(0);
        assertThrows(BizException.class, () -> pricingService.updateStoreConfig(STORE, bad, OP),
                "容量为 0 会让整个门店当天全时段约满");

        AdminPricingService.StoreConfigCmd badHours = new AdminPricingService.StoreConfigCmd();
        badHours.setBusinessHours("19:00-09:00");
        assertThrows(BizException.class, () -> pricingService.updateStoreConfig(STORE, badHours, OP),
                "结束早于开始，或跨夜未被支持时必须明确拒绝");

        AdminPricingService.StoreConfigCmd badFormat = new AdminPricingService.StoreConfigCmd();
        badFormat.setBusinessHours("9点到19点");
        assertThrows(BizException.class, () -> pricingService.updateStoreConfig(STORE, badFormat, OP));
    }

    @Test
    void storeConfigChangeIsAudited() {
        StoreConfig before = pricingService.storeConfig(STORE);
        int original = before.getCancelHours();

        AdminPricingService.StoreConfigCmd cmd = new AdminPricingService.StoreConfigCmd();
        cmd.setCancelHours(original == 6 ? 7 : 6);
        pricingService.updateStoreConfig(STORE, cmd, OP);

        List<ConfigAuditLog> logs = auditService.list(ConfigAuditLog.TARGET_STORE_CONFIG, before.getId(), 20);
        ConfigAuditLog row = logs.stream()
                .filter(l -> "免费取消时限(小时)".equals(l.getFieldName()))
                .findFirst().orElse(null);
        assertNotNull(row, "门店规则改动必须在审计里能看到是哪一项变了");
        assertEquals(String.valueOf(original), row.getBeforeValue());

        // 复原，避免影响同库其他用例
        AdminPricingService.StoreConfigCmd restore = new AdminPricingService.StoreConfigCmd();
        restore.setCancelHours(original);
        pricingService.updateStoreConfig(STORE, restore, OP);
    }

    // ============================================================ 充值档位

    @Test
    void rechargeGiftComesFromTierNotFromClient() {
        String amount = "7" + (System.nanoTime() % 1000); // 造一个不与种子档位冲突的金额
        RechargeTierService.TierCmd cmd = new RechargeTierService.TierCmd();
        cmd.setAmount(new BigDecimal(amount));
        cmd.setGift(new BigDecimal("66.00"));
        rechargeTierService.createTier(STORE, cmd, OP);

        assertEquals(0, new BigDecimal("66.00").compareTo(
                        rechargeTierService.requireGift(STORE, new BigDecimal(amount))),
                "赠送额只能由档位配置决定 —— 改造前它由请求体里的 gift 字段决定，等于让客户端自己决定送多少");

        // 金额刻度不同也算同一档：BigDecimal("300.0") 与 "300.00" 的 equals 是 false，
        // 用等值判断会让小程序传 300.0 时匹配失败，症状是「某些金额充不了」
        assertEquals(0, new BigDecimal("200.00").compareTo(
                        rechargeTierService.requireGift(STORE, new BigDecimal("1000.0"))),
                "种子档位：充 1000 送 200");
    }

    @Test
    void rechargeAmountOutsideTiersIsRejected() {
        assertThrows(BizException.class,
                () -> rechargeTierService.requireGift(STORE, new BigDecimal("123.45")),
                "不在档位内必须拒绝，而不是静默按 0 赠送 —— 后者会让客户以为「充了但没送」");
    }

    @Test
    void duplicateTierAmountIsRejected() {
        String amount = "8" + (System.nanoTime() % 1000);
        RechargeTierService.TierCmd first = new RechargeTierService.TierCmd();
        first.setAmount(new BigDecimal(amount));
        first.setGift(new BigDecimal("10.00"));
        rechargeTierService.createTier(STORE, first, OP);

        RechargeTierService.TierCmd dup = new RechargeTierService.TierCmd();
        dup.setAmount(new BigDecimal(amount));
        dup.setGift(new BigDecimal("20.00"));
        // 同一金额两个赠送规则，实际送多少取决于数据库返回顺序
        assertThrows(BizException.class, () -> rechargeTierService.createTier(STORE, dup, OP));
    }

    @Test
    void tierGiftCannotExceedPrincipal() {
        RechargeTierService.TierCmd cmd = new RechargeTierService.TierCmd();
        cmd.setAmount(new BigDecimal("100.00"));
        cmd.setGift(new BigDecimal("101.00"));
        assertThrows(BizException.class, () -> rechargeTierService.createTier(STORE, cmd, OP));
    }

    @Test
    void tierUpdateIsAudited() {
        String amount = "9" + (System.nanoTime() % 1000);
        RechargeTierService.TierCmd create = new RechargeTierService.TierCmd();
        create.setAmount(new BigDecimal(amount));
        create.setGift(new BigDecimal("11.00"));
        RechargeTier tier = rechargeTierService.createTier(STORE, create, OP);

        RechargeTierService.TierCmd update = new RechargeTierService.TierCmd();
        update.setGift(new BigDecimal("22.00"));
        rechargeTierService.updateTier(tier.getId(), update, OP);

        ConfigAuditLog row = auditService.list(ConfigAuditLog.TARGET_RECHARGE_TIER, tier.getId(), 20)
                .stream().filter(l -> "赠送".equals(l.getFieldName())).findFirst().orElse(null);
        assertNotNull(row);
        assertEquals("11.00", row.getBeforeValue());
        assertEquals("22.00", row.getAfterValue());
    }

    // ============================================================ 会员等级

    @Test
    void levelDiscountMustBeARealDiscount() {
        MemberLevel level = memberLevelService.listLevels(STORE).get(1); // 银爪

        AdminMemberLevelService.LevelCmd tooBig = new AdminMemberLevelService.LevelCmd();
        tooBig.setGeneralDiscount(new BigDecimal("1.50"));
        assertThrows(BizException.class, () -> memberLevelService.updateLevel(level.getId(), tooBig, OP),
                "大于 1 是加价，不是折扣");

        AdminMemberLevelService.LevelCmd zero = new AdminMemberLevelService.LevelCmd();
        zero.setGeneralDiscount(BigDecimal.ZERO);
        assertThrows(BizException.class, () -> memberLevelService.updateLevel(level.getId(), zero, OP),
                "0 折等于白送，必须是显式错误而不是静默接受");
    }

    @Test
    void levelThresholdMustStayOrdered() {
        List<MemberLevel> levels = memberLevelService.listLevels(STORE);
        MemberLevel silver = levels.stream().filter(l -> l.getLevel() == 2).findFirst().orElseThrow();
        MemberLevel gold = levels.stream().filter(l -> l.getLevel() == 3).findFirst().orElseThrow();

        AdminMemberLevelService.LevelCmd invert = new AdminMemberLevelService.LevelCmd();
        invert.setThreshold(gold.getThreshold().add(new BigDecimal("100")));
        BizException e = assertThrows(BizException.class,
                () -> memberLevelService.updateLevel(silver.getId(), invert, OP));
        assertTrue(e.getMessage().contains(gold.getName()),
                "只跟上一级比较是不够的：门槛越过下一级时必须报出是跟哪个等级倒挂了");

        AdminMemberLevelService.LevelCmd ok = new AdminMemberLevelService.LevelCmd();
        BigDecimal newThreshold = silver.getThreshold().add(new BigDecimal("-50"));
        ok.setThreshold(newThreshold);
        assertEquals(0, newThreshold.compareTo(
                memberLevelService.updateLevel(silver.getId(), ok, OP).getThreshold()));
    }

    @Test
    void newLevelGetsNextSequenceNumber() {
        List<MemberLevel> levels = memberLevelService.listLevels(STORE);
        int maxLevel = levels.stream().mapToInt(MemberLevel::getLevel).max().orElse(0);
        BigDecimal maxThreshold = levels.stream().map(MemberLevel::getThreshold)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        AdminMemberLevelService.LevelCmd cmd = new AdminMemberLevelService.LevelCmd();
        cmd.setName("测试等级-" + System.nanoTime());
        cmd.setThreshold(maxThreshold.add(new BigDecimal("1000")));
        cmd.setGeneralDiscount(new BigDecimal("0.85"));
        MemberLevel created = memberLevelService.createLevel(STORE, cmd, OP);

        assertEquals(maxLevel + 1, created.getLevel(), "等级序号由服务端续号，不允许客户端指定");
        assertEquals(0, created.getEnabled(), "新建等级默认关闭：折扣是花钱的事，先配好再显式开启");
    }

    // ============================================================ 次卡类型

    @Test
    void cardTypeTimesCannotDropBelowUsedTimes() {
        AdminCardTypeService.CardTypeCmd cmd = new AdminCardTypeService.CardTypeCmd();
        cmd.setName("次卡约束-" + System.nanoTime());
        cmd.setTimes(10);
        cmd.setPrice(new BigDecimal("980.00"));
        CardType type = cardTypeService.createCardType(STORE, cmd, OP);

        // 造一张"已用 6 次"的卡（total 10 / remain 4）
        Card card = new Card();
        card.setCardNo("T" + System.nanoTime());
        card.setStoreId(STORE);
        card.setMemberId(1L);
        card.setCardTypeId(type.getId());
        card.setTotalTimes(10);
        card.setRemainTimes(4);
        card.setPreDeductCount(0);
        card.setValidUntil(LocalDate.now().plusMonths(12));
        card.setStatus(Card.STATUS_USING);
        card.setPaidAmount(new BigDecimal("980.00"));
        cardMapper.insert(card);

        AdminCardTypeService.CardTypeCmd shrink = new AdminCardTypeService.CardTypeCmd();
        shrink.setTimes(5);
        BizException e = assertThrows(BizException.class,
                () -> cardTypeService.updateCardType(type.getId(), shrink, OP));
        assertTrue(e.getMessage().contains("剩余次数"), "要让店长看懂为什么不能这么改");

        AdminCardTypeService.CardTypeCmd raise = new AdminCardTypeService.CardTypeCmd();
        raise.setTimes(20);
        assertEquals(20, cardTypeService.updateCardType(type.getId(), raise, OP).getTimes());
    }

    @Test
    void fixedDateCardTypeRequiresFutureExpiry() {
        AdminCardTypeService.CardTypeCmd cmd = new AdminCardTypeService.CardTypeCmd();
        cmd.setName("固定到期-" + System.nanoTime());
        cmd.setTimes(5);
        cmd.setPrice(new BigDecimal("300.00"));
        cmd.setValidMode("FIXED");
        assertThrows(BizException.class, () -> cardTypeService.createCardType(STORE, cmd, OP),
                "固定到期模式必须给到期日");

        cmd.setValidUntil(LocalDate.now().minusDays(1));
        assertThrows(BizException.class, () -> cardTypeService.createCardType(STORE, cmd, OP),
                "发出去就已经过期的卡，买了立刻不能核销");

        cmd.setValidUntil(LocalDate.now().plusMonths(6));
        CardType created = cardTypeService.createCardType(STORE, cmd, OP);
        assertEquals("FIXED", created.getValidMode());
        assertNull(created.getValidMonths(), "FIXED 模式要清掉 valid_months，否则取值分支不可预测");
    }

    /**
     * 适用服务 / 规格的<b>存储格式契约</b>（对应审查 P1-1）。
     *
     * <p>后台前端把这两个字段当 JSON 数组文本读（{@code idListOf} 先剥 {@code [ ]} 再切逗号再剥引号），
     * 所以库里存的必须恰好是 {@code "[101,102]"} 这种形态。原先前端用
     * {@code String(t.specIds).split(',')} 直接切，首尾元素会带上方括号，
     * 勾选态永远回显不上 —— 通过接口看不出问题（字段确实有值），只有打开编辑弹层才发现是空的。
     *
     * <p>锁住这个格式，是为了让"改变存储格式"这件事无法悄悄发生：一旦后端改成别的写法，
     * 这个用例会立刻红，并且指向前端的 {@code idListOf} 需要同步 ——
     * 而不是等店长编辑卡型时发现适用范围莫名其妙丢了。
     */
    @Test
    void cardTypeServiceIdsStoredAsJsonArrayTextForFrontend() {
        AdminCardTypeService.CardTypeCmd cmd = new AdminCardTypeService.CardTypeCmd();
        cmd.setName("适用范围-" + System.nanoTime());
        cmd.setTimes(10);
        cmd.setPrice(new BigDecimal("880.00"));
        // 前端提交的是逗号分隔（checkbox 勾选结果 join(',')）
        cmd.setServiceIds("101,102");
        cmd.setSpecIds("11,12,13");
        CardType type = cardTypeService.createCardType(STORE, cmd, OP);

        CardType reloaded = cardTypeMapper.selectById(type.getId());
        assertEquals("[101,102]", reloaded.getServiceIds(), "必须落成 JSON 数组文本，前端按此解析回勾选态");
        assertEquals("[11,12,13]", reloaded.getSpecIds());

        // 反过来喂 JSON 数组文本也要能收敛到同一形态（老数据 / 直接调 API 的场景）
        AdminCardTypeService.CardTypeCmd jsonCmd = new AdminCardTypeService.CardTypeCmd();
        jsonCmd.setServiceIds("[\"101\",\"102\"]");
        jsonCmd.setSpecIds("[ 11 , 12 ]");
        CardType updated = cardTypeService.updateCardType(type.getId(), jsonCmd, OP);
        assertEquals("[101,102]", updated.getServiceIds());
        assertEquals("[11,12]", updated.getSpecIds());
    }

    /** 前端已把"至少选一个"挡在前面，服务端对空串的归一化口径也要稳定：空 → null，而不是 "[]"。 */
    @Test
    void cardTypeEmptyScopeNormalizesToNull() {
        AdminCardTypeService.CardTypeCmd cmd = new AdminCardTypeService.CardTypeCmd();
        cmd.setName("空范围-" + System.nanoTime());
        cmd.setTimes(3);
        cmd.setPrice(new BigDecimal("100.00"));
        cmd.setServiceIds("[]");
        CardType type = cardTypeService.createCardType(STORE, cmd, OP);
        assertNull(type.getServiceIds(), "\"[]\" 应归一化成 null，而不是留下一个空的 JSON 数组");
    }

    // ============================================================ 审计本身

    @Test
    void auditRefusesBlankOperator() {
        assertThrows(BizException.class, () -> auditService.single(
                ConfigAuditLog.TARGET_SKU, 1L, ConfigAuditLog.ACTION_ADJUST,
                "库存", "1", "2", "测试", "   "),
                "空操作人要报错而不能静默跳过 —— 静默跳过会让「没有审计行」同时意味着「没改」和「忘了传操作人」");
    }
}
