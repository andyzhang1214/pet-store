package cn.petshop.admin.audit;

import cn.petshop.common.audit.ConfigAuditLog;
import cn.petshop.common.audit.ConfigAuditService;
import cn.petshop.common.web.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 配置变更审计查询。放在 petshop-admin 模块里，因为它不属任何一个业务域
 * —— 而 petshop-admin 依赖全部业务模块，是唯一能同时看到所有领域的地方。
 *
 * <p>登录即可读：审计的价值在于"谁都能查"，把它锁进店长权限反而让店员无法自证
 * "这次改价不是我干的"。写入端才有权限要求，读取端没有。</p>
 */
@RestController
@RequestMapping("/admin/config-audit")
@RequiredArgsConstructor
public class AdminConfigAuditController {

    private final ConfigAuditService auditService;

    /**
     * 审计流水。
     *
     * @param targetType 可选，如 {@code SKU} / {@code CARD_TYPE}
     * @param targetId   可选，配合 targetType 查某个对象的完整变更史
     * @param limit      条数上限，服务端夹在 1-200
     */
    @GetMapping
    public R<List<ConfigAuditLog>> list(@RequestParam(required = false) String targetType,
                                        @RequestParam(required = false) Long targetId,
                                        @RequestParam(defaultValue = "100") long limit) {
        // 单店部署下不需要再按 store 过滤；多店时这里要改为按当前登录账号的 store_id 收窄，
        // 否则店长能读到别的门店的改价记录
        if (targetType != null && !targetType.isBlank()) {
            return R.ok(auditService.list(targetType.trim().toUpperCase(), targetId, limit));
        }
        return R.ok(auditService.recent(limit));
    }
}
