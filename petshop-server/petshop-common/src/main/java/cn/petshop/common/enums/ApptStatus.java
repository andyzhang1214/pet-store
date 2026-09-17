package cn.petshop.common.enums;

public enum ApptStatus {
    PENDING, ARRIVED, SETTLING, SETTLED, CANCELLED, NO_SHOW;

    /**
     * 占用时段容量的状态（SETTLING 为结算事务内的中间态，事务提交前对其他事务不可见，
     * 这里一并计入以便被取消/爽约路径正确回归）。
     */
    public static boolean isActive(String status) {
        return PENDING.name().equals(status) || ARRIVED.name().equals(status)
                || SETTLING.name().equals(status);
    }
}
