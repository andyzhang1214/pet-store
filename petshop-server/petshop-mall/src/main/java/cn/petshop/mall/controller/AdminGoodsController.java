package cn.petshop.mall.controller;

import cn.petshop.common.annotation.RequireRole;
import cn.petshop.common.context.AdminContext;
import cn.petshop.common.context.StoreContext;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.common.web.R;
import cn.petshop.mall.entity.InventoryLog;
import cn.petshop.mall.service.AdminCatalogService;
import cn.petshop.mall.service.AdminGoodsVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
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

import java.util.List;
import java.util.Map;

/**
 * 后台商品与库存配置。
 *
 * <h3>角色划分依据</h3>
 * <ul>
 *   <li><b>查询 / 库存盘点 → 店员（STAFF）</b>：这是门店日常动作。
 *       盘点会落 {@code inventory_log} + {@code config_audit_log}（含操作人与原因），
 *       属"记录在案"而非"无需授权"—— 有据可查比一刀切禁止更能反映真实作业。</li>
 *   <li><b>新建 / 改价 / 上下架 → 店长（MANAGER）</b>：这些是"改一次、影响之后每一笔账"的参数，
 *       和资金接口同级。</li>
 * </ul>
 * 不提供删除端点：{@code order_item} 存了 sku 主键，硬删会让历史订单变成孤儿。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminGoodsController {

    private final AdminCatalogService catalogService;

    // ------------------------------------------------------------------ 查询

    @GetMapping("/goods")
    public R<IPage<AdminGoodsVO>> listGoods(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size) {
        return R.ok(catalogService.listGoods(StoreContext.DEFAULT_STORE_ID, keyword, status, page, size));
    }

    @GetMapping("/goods/{id}")
    public R<AdminGoodsVO> goodsDetail(@PathVariable Long id) {
        return R.ok(catalogService.goodsDetail(id));
    }

    /** 某 SKU 的库存变动历史（销售出库 / 回补 / 盘点）。 */
    @GetMapping("/skus/{id}/inventory-logs")
    public R<List<InventoryLog>> inventoryLogs(@PathVariable Long id,
                                               @RequestParam(defaultValue = "50") long limit) {
        return R.ok(catalogService.inventoryLogs(id, limit));
    }

    // ------------------------------------------------------------ 商品（店长）

    @PostMapping("/goods")
    @RequireRole(AdminRole.MANAGER)
    public R<AdminGoodsVO> createGoods(@RequestBody AdminCatalogService.GoodsCmd cmd) {
        return R.ok(catalogService.createGoods(StoreContext.DEFAULT_STORE_ID, cmd, AdminContext.operatorName()));
    }

    @PutMapping("/goods/{id}")
    @RequireRole(AdminRole.MANAGER)
    public R<AdminGoodsVO> updateGoods(@PathVariable Long id,
                                      @RequestBody AdminCatalogService.GoodsCmd cmd) {
        return R.ok(catalogService.updateGoods(id, cmd, AdminContext.operatorName()));
    }

    @Data
    public static class StatusCmd {
        /** ON_SALE / OFF_SALE */
        private String status;
    }

    @PostMapping("/goods/{id}/status")
    @RequireRole(AdminRole.MANAGER)
    public R<AdminGoodsVO> setGoodsStatus(@PathVariable Long id, @RequestBody StatusCmd cmd) {
        if (cmd == null || cmd.getStatus() == null || cmd.getStatus().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "状态不能为空");
        }
        return R.ok(catalogService.setGoodsStatus(id, cmd.getStatus(), AdminContext.operatorName()));
    }

    @PostMapping("/goods/{id}/skus")
    @RequireRole(AdminRole.MANAGER)
    public R<AdminGoodsVO> addSku(@PathVariable Long id,
                                  @RequestBody AdminCatalogService.SkuCmd cmd) {
        return R.ok(catalogService.addSku(id, cmd, AdminContext.operatorName()));
    }

    @PutMapping("/skus/{id}")
    @RequireRole(AdminRole.MANAGER)
    public R<AdminGoodsVO> updateSku(@PathVariable Long id,
                                     @RequestBody AdminCatalogService.SkuCmd cmd) {
        return R.ok(catalogService.updateSku(id, cmd, AdminContext.operatorName()));
    }

    // -------------------------------------------------------- 库存盘点（店员）

    @Data
    public static class StockAdjustCmd {
        /** 可正可负；结果不允许为负 */
        private Integer delta;
        /** 盘点原因，必填 */
        private String remark;
    }

    @PostMapping("/skus/{id}/stock")
    @RequireRole(AdminRole.STAFF)
    public R<Map<String, Object>> adjustStock(@PathVariable Long id, @RequestBody StockAdjustCmd cmd) {
        if (cmd == null || cmd.getDelta() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "调整数量不能为空");
        }
        return R.ok(catalogService.adjustStock(id, cmd.getDelta(), cmd.getRemark(), AdminContext.operatorName()));
    }
}
