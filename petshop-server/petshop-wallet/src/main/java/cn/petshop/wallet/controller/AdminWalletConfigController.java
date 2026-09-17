package cn.petshop.wallet.controller;

import cn.petshop.common.annotation.RequireRole;
import cn.petshop.common.context.AdminContext;
import cn.petshop.common.context.StoreContext;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.common.web.R;
import cn.petshop.wallet.entity.CardType;
import cn.petshop.wallet.entity.RechargeTier;
import cn.petshop.wallet.service.AdminCardTypeService;
import cn.petshop.wallet.service.RechargeTierService;
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
 * 后台资金类配置：次卡类型与充值档位。全部为店长权限。
 *
 * <p>这两项直接决定"客户花多少钱拿到多少价值"，与退款审核同级；
 * 店员可以查（核销时要看卡型适用服务），但不能改。</p>
 *
 * <h3>都不提供删除</h3>
 * 次卡类型被 {@code card.card_type_id} 引用、充值档位被审计流水引用。
 * 停售（状态改为 DISABLED）既达到"不再卖"的目的，又不破坏历史可读性。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminWalletConfigController {

    private final AdminCardTypeService cardTypeService;
    private final RechargeTierService rechargeTierService;

    // ---------------------------------------------------------------- 次卡类型

    @GetMapping("/card-types")
    public R<List<CardType>> listCardTypes(
            @RequestParam(name = "includeDisabled", defaultValue = "true") boolean includeDisabled) {
        return R.ok(cardTypeService.listCardTypes(StoreContext.DEFAULT_STORE_ID, includeDisabled));
    }

    @PostMapping("/card-types")
    @RequireRole(AdminRole.MANAGER)
    public R<CardType> createCardType(@RequestBody AdminCardTypeService.CardTypeCmd cmd) {
        return R.ok(cardTypeService.createCardType(StoreContext.DEFAULT_STORE_ID, cmd,
                AdminContext.operatorName()));
    }

    @PutMapping("/card-types/{id}")
    @RequireRole(AdminRole.MANAGER)
    public R<CardType> updateCardType(@PathVariable Long id,
                                      @RequestBody AdminCardTypeService.CardTypeCmd cmd) {
        return R.ok(cardTypeService.updateCardType(id, cmd, AdminContext.operatorName()));
    }

    @Data
    public static class StatusCmd {
        /** 次卡类型：ENABLED / DISABLED */
        private String status;
    }

    @PostMapping("/card-types/{id}/status")
    @RequireRole(AdminRole.MANAGER)
    public R<CardType> setCardTypeStatus(@PathVariable Long id, @RequestBody StatusCmd cmd) {
        if (cmd == null || cmd.getStatus() == null || cmd.getStatus().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "状态不能为空");
        }
        return R.ok(cardTypeService.setStatus(id, cmd.getStatus(), AdminContext.operatorName()));
    }

    @GetMapping("/card-types/{id}/sold-count")
    public R<Map<String, Object>> soldCount(@PathVariable Long id) {
        return R.ok(Map.of("cardTypeId", id, "soldCount", cardTypeService.soldCount(id)));
    }

    // ---------------------------------------------------------------- 充值档位

    @GetMapping("/recharge-tiers")
    public R<List<RechargeTier>> listTiers() {
        return R.ok(rechargeTierService.listAll(StoreContext.DEFAULT_STORE_ID));
    }

    @PostMapping("/recharge-tiers")
    @RequireRole(AdminRole.MANAGER)
    public R<RechargeTier> createTier(@RequestBody RechargeTierService.TierCmd cmd) {
        return R.ok(rechargeTierService.createTier(StoreContext.DEFAULT_STORE_ID, cmd,
                AdminContext.operatorName()));
    }

    @PutMapping("/recharge-tiers/{id}")
    @RequireRole(AdminRole.MANAGER)
    public R<RechargeTier> updateTier(@PathVariable Long id,
                                      @RequestBody RechargeTierService.TierCmd cmd) {
        return R.ok(rechargeTierService.updateTier(id, cmd, AdminContext.operatorName()));
    }

    @Data
    public static class TierStatusCmd {
        /** 1 启用 / 0 停用 */
        private Integer enabled;
    }

    @PostMapping("/recharge-tiers/{id}/status")
    @RequireRole(AdminRole.MANAGER)
    public R<RechargeTier> setTierStatus(@PathVariable Long id, @RequestBody TierStatusCmd cmd) {
        if (cmd == null || cmd.getEnabled() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "启用标记不能为空");
        }
        RechargeTierService.TierCmd tierCmd = new RechargeTierService.TierCmd();
        tierCmd.setEnabled(cmd.getEnabled());
        return R.ok(rechargeTierService.updateTier(id, tierCmd, AdminContext.operatorName()));
    }
}
