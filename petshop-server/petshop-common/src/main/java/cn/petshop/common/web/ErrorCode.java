package cn.petshop.common.web;

import lombok.Getter;

@Getter
public enum ErrorCode {
    BIZ_BALANCE_NOT_ENOUGH(1001, "余额不足"),
    BIZ_SLOT_FULL(1002, "该时段已约满，请选择邻近时段"),
    BIZ_APPT_NOT_CANCELLABLE(1003, "已过免费取消时限，请联系门店处理"),
    BIZ_VERIFY_CODE_INVALID(1004, "核销码无效或已使用"),
    BIZ_CARD_NOT_USABLE(1005, "次卡不可用（已过期 / 次数不足 / 不适用该服务）"),
    BIZ_SPEC_NOT_MATCH(1006, "该宠物规格暂无对应价格档，请联系门店"),
    BIZ_STATE_CONFLICT(1007, "状态冲突，请刷新后重试"),
    BIZ_NOT_FOUND(1008, "资源不存在"),
    BIZ_NO_GUARANTEE_DISABLED(1009, "本店需预约担保，请选择冻结余额或次卡预扣"),
    BIZ_PARAM_INVALID(1010, "参数不合法"),
    BIZ_PERMISSION_DENIED(1011, "无权访问该资源"),
    BIZ_CARD_GUARANTEE_REQUIRED(1012, "次卡担保需指定可用次卡"),
    /** 后台账号相关。与 C 端错误码分段，便于日志里一眼区分是谁的会话出了问题 */
    BIZ_ADMIN_CREDENTIAL_INVALID(1021, "账号或口令不正确"),
    BIZ_ADMIN_ACCOUNT_DISABLED(1022, "账号已停用，请联系店长"),
    BIZ_ADMIN_LOGIN_LOCKED(1023, "连续登录失败次数过多，账号已临时锁定"),
    BIZ_ADMIN_MUST_CHANGE_PASSWORD(1024, "首次登录必须先修改初始口令"),
    BIZ_ADMIN_ROLE_DENIED(1025, "当前角色无权执行该操作，请让店长操作");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
