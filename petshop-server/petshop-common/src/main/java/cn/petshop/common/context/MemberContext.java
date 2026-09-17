package cn.petshop.common.context;

import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;

public final class MemberContext {
    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private MemberContext() {
    }

    public static void set(Long memberId) {
        HOLDER.set(memberId);
    }

    public static Long get() {
        return HOLDER.get();
    }

    public static Long require() {
        Long id = HOLDER.get();
        if (id == null) {
            throw new BizException(ErrorCode.BIZ_PERMISSION_DENIED);
        }
        return id;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
