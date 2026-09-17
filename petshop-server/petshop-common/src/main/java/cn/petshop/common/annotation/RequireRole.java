package cn.petshop.common.annotation;

import cn.petshop.common.enums.AdminRole;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明访问某个后台端点所需的最低角色。由 {@code AdminTokenInterceptor} 在 preHandle 里读取并校验。
 *
 * <p>可以标在方法上，也可以标在类上（类上相当于给其中所有端点设一个下限，方法上的注解会覆盖它）。
 *
 * <h3>为什么用注解而不是在方法体里写 if</h3>
 * 在方法体里判断角色，第一个端点和第十个端点之间一定会漏掉几个 —— 而漏掉的那个
 * 恰好可能是「人工退款」。注解写在签名旁边，接口列表（Swagger / 代码检索）能直接看出来
 * 哪些端点要店长，审阅时不容易被略过。
 *
 * <p>默认值取 {@link AdminRole#MANAGER} 而不是 {@code STAFF}：
 * 万一有人只写了 {@code @RequireRole} 忘了填值，落在"更严格"那一侧是安全的。
 * 默认放开会让一次笔误变成资金接口裸奔。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {

    /** 允许访问所需的最低角色。 */
    AdminRole value() default AdminRole.MANAGER;
}
