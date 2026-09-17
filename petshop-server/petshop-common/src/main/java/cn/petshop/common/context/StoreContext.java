package cn.petshop.common.context;

/**
 * 门店上下文。
 *
 * 当前为单店部署，门店 ID 统一收敛到此处，替换此前散落在
 * BookingController / PriceMatchService / PetService / HealthArchiveService / RechargeController
 * 中的字面量 {@code 1L}（对应审查 MINOR-1）。
 *
 * 多店扩展时：把 {@link #currentStoreId()} 改为从登录态 / 请求头解析，
 * 其余调用点无需再改。
 */
public final class StoreContext {

    /** 单店部署的默认门店 ID */
    public static final long DEFAULT_STORE_ID = 1L;

    private StoreContext() {
    }

    public static Long currentStoreId() {
        return DEFAULT_STORE_ID;
    }
}
