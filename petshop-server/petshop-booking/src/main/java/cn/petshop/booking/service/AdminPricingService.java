package cn.petshop.booking.service;

import cn.petshop.booking.entity.ServiceItem;
import cn.petshop.booking.entity.ServicePrice;
import cn.petshop.booking.entity.Spec;
import cn.petshop.booking.entity.StoreConfig;
import cn.petshop.booking.mapper.ServiceMapper;
import cn.petshop.booking.mapper.ServicePriceMapper;
import cn.petshop.booking.mapper.SpecMapper;
import cn.petshop.booking.mapper.StoreConfigMapper;
import cn.petshop.common.audit.ConfigAuditLog;
import cn.petshop.common.audit.ConfigAuditService;
import cn.petshop.common.enums.ServiceCategory;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 后台服务项目与价目配置。
 *
 * <h3>价目表为什么整表读、单格写</h3>
 * 后台价目页是一张「服务 × 规格」的矩阵。读的时候必须把<b>空档也返回</b>
 * （{@code price} 为 null），否则店长看到的是一个"每个组合都有价"的表格，
 * 而实际空档处的会员会收到「该宠物规格暂无对应价格档」。
 * 写的时候按格子提交，只改得到的那一格 —— 避免整表覆盖把别人刚改的数字冲掉。
 *
 * <h3>改价影响范围</h3>
 * {@code appointment.price} 存的是下单时快照，因此改价只影响之后的新预约，
 * 在途预约与已结算单不受影响。
 */
@Service
@RequiredArgsConstructor
public class AdminPricingService {

    /** 单个服务的时长上限（slot 数）。超过半天基本是误填。 */
    private static final int MAX_DURATION_SLOTS = 16;

    /** 与 {@code StoreConfig.NO_SHOW_*} 常量保持一致 */
    private static final Set<String> NO_SHOW_POLICIES = Set.of(
            StoreConfig.NO_SHOW_RECORD, StoreConfig.NO_SHOW_DEDUCT_ALL, StoreConfig.NO_SHOW_DEDUCT_HALF,
            StoreConfig.NO_SHOW_CARD_CONFIRM, StoreConfig.NO_SHOW_CARD_RELEASE);

    /** HH:mm-HH:mm */
    private static final Pattern BUSINESS_HOURS_PATTERN = Pattern.compile("^\\d{2}:\\d{2}-\\d{2}:\\d{2}$");

    private final ServiceMapper serviceMapper;
    private final ServicePriceMapper priceMapper;
    private final SpecMapper specMapper;
    private final StoreConfigMapper storeConfigMapper;
    private final ConfigAuditService auditService;

    // ------------------------------------------------------------------ 服务项

    public List<ServiceItem> listServices(Long storeId, boolean includeDisabled) {
        LambdaQueryWrapper<ServiceItem> q = new LambdaQueryWrapper<ServiceItem>()
                .eq(ServiceItem::getStoreId, storeId)
                .orderByAsc(ServiceItem::getId);
        if (!includeDisabled) {
            q.eq(ServiceItem::getStatus, ServiceItem.STATUS_ENABLED);
        }
        return new ArrayList<>(serviceMapper.selectList(q));
    }

    @Data
    public static class ServiceCmd {
        private String name;
        /** WASH / GROOM / SPA / BOARD / VACCINE / WORM */
        private String category;
        private Integer durationSlots;
        private Integer capacity;
    }

    @Transactional
    public ServiceItem createService(Long storeId, ServiceCmd cmd, String operator) {
        validateServiceCmd(cmd, true);
        ServiceItem item = new ServiceItem();
        item.setStoreId(storeId);
        item.setName(cmd.getName().trim());
        item.setCategory(cmd.getCategory().trim().toUpperCase());
        item.setDurationSlots(cmd.getDurationSlots() == null ? 1 : cmd.getDurationSlots());
        item.setCapacity(cmd.getCapacity() == null ? 3 : cmd.getCapacity());
        // 新建后先停用：价目还没配，上架等于把一个点进去就报"暂无价格档"的服务挂到会员端
        item.setStatus(ServiceItem.STATUS_DISABLED);
        item.setCreatedAt(LocalDateTime.now());
        serviceMapper.insert(item);

        auditService.created(ConfigAuditLog.TARGET_SERVICE, item.getId(),
                "新建服务：" + item.getName() + "（" + item.getCategory() + "）", operator);
        return item;
    }

    @Transactional
    public ServiceItem updateService(Long serviceId, ServiceCmd cmd, String operator) {
        ServiceItem item = requireService(serviceId);
        validateServiceCmd(cmd, false);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("名称", item.getName());
        before.put("分类", item.getCategory());
        before.put("占用时段数", item.getDurationSlots());
        before.put("同时接待量", item.getCapacity());

        if (cmd.getName() != null && !cmd.getName().isBlank()) {
            item.setName(cmd.getName().trim());
        }
        if (cmd.getCategory() != null && !cmd.getCategory().isBlank()) {
            item.setCategory(cmd.getCategory().trim().toUpperCase());
        }
        if (cmd.getDurationSlots() != null) {
            item.setDurationSlots(cmd.getDurationSlots());
        }
        if (cmd.getCapacity() != null) {
            item.setCapacity(cmd.getCapacity());
        }
        serviceMapper.updateById(item);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("名称", item.getName());
        after.put("分类", item.getCategory());
        after.put("占用时段数", item.getDurationSlots());
        after.put("同时接待量", item.getCapacity());
        auditService.diff(ConfigAuditLog.TARGET_SERVICE, serviceId, before, after, null, operator);
        return item;
    }

    /**
     * 启用 / 停用服务。
     *
     * <p>启用前要求该服务<b>至少有一个价格档</b>：否则会员端会展示一个
     * 点进去就报错的服务项，而这类问题在后台看是"已上架"，排查时会先怀疑前端。</p>
     */
    @Transactional
    public ServiceItem setServiceStatus(Long serviceId, String status, String operator) {
        ServiceItem item = requireService(serviceId);
        String target = status == null ? "" : status.trim().toUpperCase();
        if (!ServiceItem.STATUS_ENABLED.equals(target) && !ServiceItem.STATUS_DISABLED.equals(target)) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "状态只能是 ENABLED 或 DISABLED");
        }
        if (target.equals(item.getStatus())) {
            return item;
        }
        if (ServiceItem.STATUS_ENABLED.equals(target) && listPrices(serviceId).isEmpty()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "该服务还没有配置任何价格档，无法启用");
        }
        String before = item.getStatus();
        item.setStatus(target);
        serviceMapper.updateById(item);
        auditService.single(ConfigAuditLog.TARGET_SERVICE, serviceId, ConfigAuditLog.ACTION_STATUS,
                "状态", before, target, null, operator);
        return item;
    }

    // -------------------------------------------------------------------- 价目

    /** 价目矩阵的一行：规格信息 + 该服务在此规格下的价格（可能为 null = 空档）。 */
    @Data
    public static class PriceRow {
        private Long specId;
        private String specName;
        private String petType;
        private String size;
        private String hair;
        private BigDecimal price;

        /** 是否为「空档」：会员端按此规格匹配到该服务时会报"暂无价格档" */
        public boolean isMissing() {
            return price == null;
        }
    }

    /** 某服务的完整价目矩阵（含空档）。 */
    public List<PriceRow> priceMatrix(Long storeId, Long serviceId) {
        requireService(serviceId);
        Map<Long, BigDecimal> priced = new LinkedHashMap<>();
        for (ServicePrice p : priceMapper.selectList(new LambdaQueryWrapper<ServicePrice>()
                .eq(ServicePrice::getServiceId, serviceId))) {
            priced.put(p.getSpecId(), p.getPrice());
        }
        List<PriceRow> rows = new ArrayList<>();
        for (Spec spec : listSpecs(storeId)) {
            PriceRow row = new PriceRow();
            row.setSpecId(spec.getId());
            row.setSpecName(spec.getName());
            row.setPetType(spec.getPetType());
            row.setSize(spec.getSize());
            row.setHair(spec.getHair());
            row.setPrice(priced.get(spec.getId()));
            rows.add(row);
        }
        return rows;
    }

    /** 该服务已配置的价格行（不含空档），用于"能否启用"的判断。 */
    public List<ServicePrice> listPrices(Long serviceId) {
        return new ArrayList<>(priceMapper.selectList(new LambdaQueryWrapper<ServicePrice>()
                .eq(ServicePrice::getServiceId, serviceId)));
    }

    /**
     * 设置某一格价格。已存在则改、不存在则建（按 service_id + spec_id 唯一约束）。
     */
    @Transactional
    public PriceRow setPrice(Long serviceId, Long specId, BigDecimal price, String operator) {
        requireService(serviceId);
        Spec spec = requireSpec(specId);
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "价格必须大于 0；如需停售请停用整个服务");
        }
        ServicePrice existing = priceMapper.selectOne(new LambdaQueryWrapper<ServicePrice>()
                .eq(ServicePrice::getServiceId, serviceId)
                .eq(ServicePrice::getSpecId, specId));
        String before = existing == null ? null : String.valueOf(existing.getPrice());
        if (existing == null) {
            ServicePrice row = new ServicePrice();
            row.setServiceId(serviceId);
            row.setSpecId(specId);
            row.setPrice(price);
            priceMapper.insert(row);
        } else {
            if (existing.getPrice() != null && existing.getPrice().compareTo(price) == 0) {
                PriceRow same = new PriceRow();
                same.setSpecId(specId);
                same.setSpecName(spec.getName());
                same.setPrice(price);
                return same;
            }
            existing.setPrice(price);
            priceMapper.updateById(existing);
        }

        // 审计对象是「服务×规格」这一格，target_id 用 specId，
        // remark 里带上服务标识，便于按服务维度读变更史
        auditService.single(ConfigAuditLog.TARGET_SERVICE_PRICE, specId, ConfigAuditLog.ACTION_UPDATE,
                "价格", before, String.valueOf(price), "服务#" + serviceId + " " + spec.getName(), operator);

        PriceRow row = new PriceRow();
        row.setSpecId(specId);
        row.setSpecName(spec.getName());
        row.setPetType(spec.getPetType());
        row.setSize(spec.getSize());
        row.setHair(spec.getHair());
        row.setPrice(price);
        return row;
    }

    // -------------------------------------------------------------------- 规格

    /** 规格字典。规格决定"哪些宠物算同一档"，是价目表的行头，属于配置域。 */
    public List<Spec> listSpecs(Long storeId) {
        return new ArrayList<>(specMapper.selectList(new LambdaQueryWrapper<Spec>()
                .eq(Spec::getStoreId, storeId)
                .orderByAsc(Spec::getPetType)
                .orderByAsc(Spec::getSize)
                .orderByAsc(Spec::getHair)));
    }

    @Data
    public static class SpecCmd {
        /** CAT / DOG */
        private String petType;
        /** SMALL / MEDIUM / LARGE；为空 = 通吃该体型 */
        private String size;
        /** SHORT / MEDIUM_LONG / LONG；为空 = 通吃该毛长 */
        private String hair;
        private String name;
    }

    /**
     * 新增规格档。
     *
     * <p>{@code size} / {@code hair} 允许为空，语义是"不限"：{@code PriceMatchService}
     * 按约束数量取最具体的档位，所以空值档是兜底档。
     * 唯一约束 {@code uk_spec(store_id, pet_type, size, hair)} 在库里，这里先查一次是为了
     * 把冲突变成一句人话，而不是把一个 {@code DuplicateKeyException} 抛到界面上。</p>
     */
    @Transactional
    public Spec createSpec(Long storeId, SpecCmd cmd, String operator) {
        if (cmd == null || cmd.getPetType() == null || cmd.getPetType().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "宠物类型不能为空（CAT / DOG）");
        }
        if (cmd.getName() == null || cmd.getName().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "规格名称不能为空");
        }
        String petType = cmd.getPetType().trim().toUpperCase();
        String size = upperOrNull(cmd.getSize());
        String hair = upperOrNull(cmd.getHair());

        Spec dup = specMapper.selectOne(new LambdaQueryWrapper<Spec>()
                .eq(Spec::getStoreId, storeId)
                .eq(Spec::getPetType, petType)
                .eq(size != null, Spec::getSize, size)
                .isNull(size == null, Spec::getSize)
                .eq(hair != null, Spec::getHair, hair)
                .isNull(hair == null, Spec::getHair));
        if (dup != null) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT,
                    "该规格已存在（" + dup.getName() + "），不能重复添加");
        }

        Spec spec = new Spec();
        spec.setStoreId(storeId);
        spec.setPetType(petType);
        spec.setSize(size);
        spec.setHair(hair);
        spec.setName(cmd.getName().trim());
        specMapper.insert(spec);

        auditService.created(ConfigAuditLog.TARGET_SERVICE_PRICE, spec.getId(),
                "新增规格档：" + spec.getName(), operator);
        return spec;
    }

    // ------------------------------------------------------------------ 门店规则

    @Data
    public static class StoreConfigCmd {
        private String name;
        /** 免费取消时限（小时） */
        private Integer cancelHours;
        /** 是否启用"无需担保"预约 */
        private Boolean noGuaranteeEnabled;
        /** RECORD / DEDUCT_ALL / DEDUCT_HALF / CARD_CONFIRM / CARD_RELEASE */
        private String noShowPolicy;
        private Integer slotCapacity;
        private Integer bookableDays;
        /** HH:mm-HH:mm */
        private String businessHours;
    }

    /**
     * 更新门店规则。
     *
     * <p>这些值都在<b>下单路径上被读</b>（容量决定时段能否约上、取消时限决定能否自助取消、
     * 可约天数决定日历放开多远），因此每项都做范围校验：
     * 一个 {@code slotCapacity=0} 的配置会让整个门店当天全时段约满，
     * 而这种事故从界面提示里看不出来。</p>
     */
    @Transactional
    public StoreConfig updateStoreConfig(Long storeId, StoreConfigCmd cmd, String operator) {
        if (cmd == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请求体不能为空");
        }
        StoreConfig cfg = storeConfigMapper.selectByStoreId(storeId);
        if (cfg == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "门店配置不存在");
        }

        validateRange(cmd.getCancelHours(), 0, 168, "免费取消时限（小时）");
        validateRange(cmd.getSlotCapacity(), 1, 99, "时段容量");
        validateRange(cmd.getBookableDays(), 1, 90, "可约天数");
        if (cmd.getNoShowPolicy() != null && !cmd.getNoShowPolicy().isBlank()) {
            String policy = cmd.getNoShowPolicy().trim().toUpperCase();
            if (!NO_SHOW_POLICIES.contains(policy)) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                        "爽约策略不合法，可选：" + String.join(" / ", NO_SHOW_POLICIES));
            }
        }
        String hours = null;
        if (cmd.getBusinessHours() != null && !cmd.getBusinessHours().isBlank()) {
            hours = cmd.getBusinessHours().trim();
            if (!BUSINESS_HOURS_PATTERN.matcher(hours).matches()) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "营业时间格式应为 HH:mm-HH:mm，例如 09:00-19:00");
            }
            int start = toMinutes(hours.substring(0, 5));
            int end = toMinutes(hours.substring(6, 11));
            if (end <= start) {
                // 跨夜营业是另一个产品形态（需要处理"次日时段归属哪一天"），当前不支持就明确拒绝
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "结束时间必须晚于开始时间（暂不支持跨夜营业）");
            }
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("门店名", cfg.getName());
        before.put("免费取消时限(小时)", cfg.getCancelHours());
        before.put("允许无需担保预约", cfg.getNoGuaranteeEnabled());
        before.put("爽约策略", cfg.getNoShowPolicy());
        before.put("时段容量", cfg.getSlotCapacity());
        before.put("可约天数", cfg.getBookableDays());
        before.put("营业时间", cfg.getBusinessHours());

        if (cmd.getName() != null && !cmd.getName().isBlank()) {
            cfg.setName(cmd.getName().trim());
        }
        if (cmd.getCancelHours() != null) {
            cfg.setCancelHours(cmd.getCancelHours());
        }
        if (cmd.getNoGuaranteeEnabled() != null) {
            cfg.setNoGuaranteeEnabled(cmd.getNoGuaranteeEnabled());
        }
        if (cmd.getNoShowPolicy() != null && !cmd.getNoShowPolicy().isBlank()) {
            cfg.setNoShowPolicy(cmd.getNoShowPolicy().trim().toUpperCase());
        }
        if (cmd.getSlotCapacity() != null) {
            cfg.setSlotCapacity(cmd.getSlotCapacity());
        }
        if (cmd.getBookableDays() != null) {
            cfg.setBookableDays(cmd.getBookableDays());
        }
        if (hours != null) {
            cfg.setBusinessHours(hours);
        }
        cfg.setUpdatedAt(LocalDateTime.now());
        storeConfigMapper.updateById(cfg);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("门店名", cfg.getName());
        after.put("免费取消时限(小时)", cfg.getCancelHours());
        after.put("允许无需担保预约", cfg.getNoGuaranteeEnabled());
        after.put("爽约策略", cfg.getNoShowPolicy());
        after.put("时段容量", cfg.getSlotCapacity());
        after.put("可约天数", cfg.getBookableDays());
        after.put("营业时间", cfg.getBusinessHours());
        auditService.diff(ConfigAuditLog.TARGET_STORE_CONFIG, cfg.getId(), before, after,
                "门店#" + storeId, operator);
        return cfg;
    }

    /** 读当前门店规则。 */
    public StoreConfig storeConfig(Long storeId) {
        StoreConfig cfg = storeConfigMapper.selectByStoreId(storeId);
        if (cfg == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "门店配置不存在");
        }
        return cfg;
    }

    private void validateRange(Integer value, int min, int max, String label) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                    label + "需在 " + min + "-" + max + " 之间");
        }
    }

    private int toMinutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    private void validateServiceCmd(ServiceCmd cmd, boolean create) {
        if (cmd == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请求体不能为空");
        }
        if (create && (cmd.getName() == null || cmd.getName().isBlank())) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "服务名称不能为空");
        }
        if (create && (cmd.getCategory() == null || cmd.getCategory().isBlank())) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "服务分类不能为空");
        }
        if (cmd.getCategory() != null && !cmd.getCategory().isBlank()) {
            try {
                ServiceCategory.valueOf(cmd.getCategory().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "服务分类不合法：" + cmd.getCategory());
            }
        }
        if (cmd.getDurationSlots() != null
                && (cmd.getDurationSlots() < 1 || cmd.getDurationSlots() > MAX_DURATION_SLOTS)) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                    "占用时段数需在 1-" + MAX_DURATION_SLOTS + " 之间");
        }
        if (cmd.getCapacity() != null && (cmd.getCapacity() < 1 || cmd.getCapacity() > 99)) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "同时接待量需在 1-99 之间");
        }
    }

    private ServiceItem requireService(Long serviceId) {
        if (serviceId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "服务 ID 不能为空");
        }
        ServiceItem item = serviceMapper.selectById(serviceId);
        if (item == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "服务不存在");
        }
        return item;
    }

    private Spec requireSpec(Long specId) {
        if (specId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "规格 ID 不能为空");
        }
        Spec spec = specMapper.selectById(specId);
        if (spec == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "规格不存在");
        }
        return spec;
    }

    private String upperOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim().toUpperCase();
    }
}
