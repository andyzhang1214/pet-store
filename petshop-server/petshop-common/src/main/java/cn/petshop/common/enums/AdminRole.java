package cn.petshop.common.enums;

/**
 * 后台账号角色。
 *
 * <p>只区分两级，不做细粒度权限点：单店场景下真正的分界线只有一条 ——
 * <b>谁能动钱</b>。店长可以审退款、改资金相关配置（价目、卡类型、充值档位）、
 * 做人工退款与次卡调整；店员负责日常的核销、结算、查单、查会员。
 * 把这几个动作拆成十几个权限点，只会让配置成本和出错概率上升，收益为零。
 */
public enum AdminRole {

    /** 店长：全量权限，含所有资金动作 */
    MANAGER("店长"),
    /** 店员：日常运营，不可审退款、不可改资金配置 */
    STAFF("店员");

    private final String label;

    AdminRole(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 判断本角色是否满足某个最低要求。
     *
     * <p>写成「最小要求」而不是「恰好等于」：注解上标 {@code STAFF} 的端点，
     * 店长当然也该能用。若用等值比较，每加一个角色就得回头把所有注解重新看一遍。
     */
    public boolean satisfies(AdminRole required) {
        return required == null || this == MANAGER || required == STAFF;
    }

    /** 从字符串解析，无法识别时返回 {@code null}（由调用方决定是拒绝还是降级）。 */
    public static AdminRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
