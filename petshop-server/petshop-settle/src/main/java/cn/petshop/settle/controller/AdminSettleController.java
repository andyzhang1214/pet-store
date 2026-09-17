package cn.petshop.settle.controller;

import cn.petshop.booking.entity.Appointment;
import cn.petshop.booking.service.AppointmentService;
import cn.petshop.common.context.AdminContext;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.common.web.R;
import cn.petshop.settle.service.SettleService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台核销与结算。/admin/** 由 petshop-admin 的 AdminTokenInterceptor 统一鉴权。
 *
 * <h3>结算与改价的权限分界（对应审查 P3-8）</h3>
 * {@link AdminRole} 的口径是「店员负责日常的核销、结算」，所以结算接口本身不设角色限制 ——
 * 那是收银台的日常动作，锁死就没法营业了。
 * 但 {@code manualPrice}（人工改价）会直接决定这张单实收多少，属于"动钱"：
 * 与退款审核、人工退款同级，需要店长。判定写在方法体里而不是用 {@code @RequireRole} 注解，
 * 因为注解无法按请求体内容分级。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminSettleController {

    private final AppointmentService appointmentService;
    private final SettleService settleService;

    @Data
    public static class VerifyCmd {
        private String code;
    }

    @PostMapping("/verify")
    public R<AppointmentService.ApptVO> verify(@RequestBody VerifyCmd cmd) {
        // code 缺失时不应 NPE 成 500，统一按参数错误返回（对应审查 MINOR-3）
        if (cmd == null || cmd.getCode() == null || cmd.getCode().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "核销码不能为空");
        }
        Appointment appt = appointmentService.checkinByVerifyCode(cmd.getCode().trim());
        return R.ok(appointmentService.toVO(appt));
    }

    @PostMapping("/settle/{apptId}/confirm")
    public R<SettleService.ConfirmResult> confirm(@PathVariable Long apptId,
                                                 @RequestBody SettleService.ConfirmCmd cmd) {
        // 只对"改价"分级：不带 manualPrice 的常规结算店员照常操作。
        if (cmd != null && cmd.getManualPrice() != null) {
            AdminRole role = AdminContext.require().role();
            if (role == null || !role.satisfies(AdminRole.MANAGER)) {
                throw new BizException(ErrorCode.BIZ_PERMISSION_DENIED,
                        "人工改价会直接改变实收金额，需要店长权限");
            }
        }
        return R.ok(settleService.confirm(apptId, cmd, AdminContext.operatorName()));
    }
}
