package cn.petshop.booking.controller;

import cn.petshop.booking.entity.ServiceItem;
import cn.petshop.booking.entity.ServicePrice;
import cn.petshop.booking.entity.Spec;
import cn.petshop.booking.entity.StoreConfig;
import cn.petshop.booking.service.AdminPricingService;
import cn.petshop.common.annotation.RequireRole;
import cn.petshop.common.context.AdminContext;
import cn.petshop.common.context.StoreContext;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.common.web.R;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 后台服务项目 / 价目 / 规格 / 门店规则配置。全部为店长权限。
 *
 * <h3>为什么这几项都要求店长</h3>
 * 价目、规格、门店规则都是「改一次、影响之后每一笔账」的参数 ——
 * 和退款审核同一量级。店员能查（会员端与后台列表都能看），但不能改。
 *
 * <h3>查询端点为何不设角色</h3>
 * 不标 {@code @RequireRole} 即"登录即可"。后台列表页要展示服务名与价格，
 * 若读也需要店长，店员连核销时看服务内容都做不到。读与写的权限线必须分开画。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminPricingController {

    private final AdminPricingService pricingService;

    // ------------------------------------------------------------------ 服务项

    @GetMapping("/services")
    public R<List<ServiceItem>> listServices(
            @RequestParam(name = "includeDisabled", defaultValue = "true") boolean includeDisabled) {
        return R.ok(pricingService.listServices(StoreContext.DEFAULT_STORE_ID, includeDisabled));
    }

    @PostMapping("/services")
    @RequireRole(AdminRole.MANAGER)
    public R<ServiceItem> createService(@RequestBody AdminPricingService.ServiceCmd cmd) {
        return R.ok(pricingService.createService(StoreContext.DEFAULT_STORE_ID, cmd, AdminContext.operatorName()));
    }

    @PutMapping("/services/{id}")
    @RequireRole(AdminRole.MANAGER)
    public R<ServiceItem> updateService(@PathVariable Long id,
                                       @RequestBody AdminPricingService.ServiceCmd cmd) {
        return R.ok(pricingService.updateService(id, cmd, AdminContext.operatorName()));
    }

    @Data
    public static class StatusCmd {
        /** ENABLED / DISABLED */
        private String status;
    }

    @PostMapping("/services/{id}/status")
    @RequireRole(AdminRole.MANAGER)
    public R<ServiceItem> setServiceStatus(@PathVariable Long id, @RequestBody StatusCmd cmd) {
        if (cmd == null || cmd.getStatus() == null || cmd.getStatus().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "状态不能为空");
        }
        return R.ok(pricingService.setServiceStatus(id, cmd.getStatus(), AdminContext.operatorName()));
    }

    // -------------------------------------------------------------------- 价目

    /** 价目矩阵：含「空档」（price 为 null 的格子）。 */
    @GetMapping("/services/{id}/prices")
    public R<List<AdminPricingService.PriceRow>> priceMatrix(@PathVariable Long id) {
        return R.ok(pricingService.priceMatrix(StoreContext.DEFAULT_STORE_ID, id));
    }

    @Data
    public static class PriceCmd {
        private Long specId;
        private BigDecimal price;
    }

    /** 设置某一格价格；已存在则改、不存在则建。 */
    @PutMapping("/services/{id}/prices")
    @RequireRole(AdminRole.MANAGER)
    public R<AdminPricingService.PriceRow> setPrice(@PathVariable Long id, @RequestBody PriceCmd cmd) {
        if (cmd == null || cmd.getSpecId() == null || cmd.getPrice() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "规格与价格都不能为空");
        }
        return R.ok(pricingService.setPrice(id, cmd.getSpecId(), cmd.getPrice(), AdminContext.operatorName()));
    }

    /** 已配置的价格行（不含空档）。 */
    @GetMapping("/services/{id}/price-rows")
    public R<List<ServicePrice>> priceRows(@PathVariable Long id) {
        return R.ok(pricingService.listPrices(id));
    }

    // -------------------------------------------------------------------- 规格

    @GetMapping("/specs")
    public R<List<Spec>> listSpecs() {
        return R.ok(pricingService.listSpecs(StoreContext.DEFAULT_STORE_ID));
    }

    @PostMapping("/specs")
    @RequireRole(AdminRole.MANAGER)
    public R<Spec> createSpec(@RequestBody AdminPricingService.SpecCmd cmd) {
        return R.ok(pricingService.createSpec(StoreContext.DEFAULT_STORE_ID, cmd, AdminContext.operatorName()));
    }

    // ------------------------------------------------------------------ 门店规则

    @GetMapping("/store-config")
    public R<StoreConfig> storeConfig() {
        return R.ok(pricingService.storeConfig(StoreContext.DEFAULT_STORE_ID));
    }

    @PutMapping("/store-config")
    @RequireRole(AdminRole.MANAGER)
    public R<StoreConfig> updateStoreConfig(@RequestBody AdminPricingService.StoreConfigCmd cmd) {
        return R.ok(pricingService.updateStoreConfig(StoreContext.DEFAULT_STORE_ID, cmd,
                AdminContext.operatorName()));
    }
}
