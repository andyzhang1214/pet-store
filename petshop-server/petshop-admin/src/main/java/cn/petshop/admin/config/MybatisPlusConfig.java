package cn.petshop.admin.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件。
 *
 * <h3>为什么必须有这个类</h3>
 * {@code selectPage} 依赖 {@link PaginationInnerInterceptor} 才能改写 SQL。插件不注册时，
 * MyBatis-Plus <b>不会报错</b>：它照常执行查询、照常返回 {@code IPage}，只是
 * ① count 语句根本不执行，{@code total}/{@code pages} 恒为 0；
 * ② 查询语句不会被拼上 {@code LIMIT}，{@code size} 被静默忽略，一次返回全表。
 *
 * <p>也就是说，service 里那些「page 至少 1、size 上限 100」的收敛代码在缺插件时全部失效 ——
 * 它防的是入参放大，防不住「分页根本没发生」。这类缺陷在数据量小的时候完全看不出来，
 * 等退款单/订单/流水攒到几万条才会以 OOM 或慢查询的形式暴露。
 *
 * <p>{@code PaginationInnerInterceptor} 必须注册在链上（顺序上分页通常放最后），
 * 且需显式指定 {@link DbType}：多数据源或 H2 测试库下由方言自动嗅探容易出偏差。
 *
 * <p>{@code maxLimit} 是插件层的兜底上限：即使某个调用点漏写了 size 收敛，
 * 单页也不会拉超过 {@value #MAX_LIMIT} 条。
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页硬上限，与各 service 内 MAX_PAGE_SIZE 保持同一量级。 */
    private static final long MAX_LIMIT = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_LIMIT);
        // 请求页码超过总页数时返回空集，而不是回到第一页：
        // 回到第一页会让前端「翻到底」看到重复数据，误判为还有下一页。
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
