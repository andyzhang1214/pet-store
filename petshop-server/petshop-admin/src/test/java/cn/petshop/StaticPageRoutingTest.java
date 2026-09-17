package cn.petshop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台静态页路由回归（2026-09-16）：
 * - /manage、/manage/、/ 曾无映射，落到静态资源处理器抛 NoResourceFoundException，
 *   又被兜底异常处理器吞成 500，浏览器直接显示 {"code":500,...}。
 * - 现约定：裸路径 302 到 /manage/index.html；真实不存在的资源返回 404 JSON，不得报 500。
 *
 * <h3>⚠️ 为什么这个类必须自带一个独立数据库</h3>
 * {@code @AutoConfigureMockMvc} 会生成一个与普通 {@code @SpringBootTest} 不同的上下文缓存键，
 * 于是同一 JVM 里出现两个 ApplicationContext；{@code application-test.yml} 的库名固定且进程常驻，
 * 第二个上下文启动时会把 {@code db/01_schema.sql} 再跑一遍，撞上裸 {@code CREATE INDEX}
 * 报 {@code Index "idx_ledger_member" already exists} 而启动失败。
 * 症状是「单跑绿、全量红」，与 {@link AdminRoleGuardTest} 踩过的是同一个坑 ——
 * 那里已写下「换上下文缓存键的测试必须显式指定独立 H2 库名」的约定，本类照办。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:petshop-static-routing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class StaticPageRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bareManageRedirectsToIndex() throws Exception {
        mockMvc.perform(get("/manage"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage/index.html"));
    }

    @Test
    void manageWithTrailingSlashRedirectsToIndex() throws Exception {
        mockMvc.perform(get("/manage/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage/index.html"));
    }

    @Test
    void rootRedirectsToManage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage/index.html"));
    }

    @Test
    void manageIndexServedAsStaticResource() throws Exception {
        mockMvc.perform(get("/manage/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownPathIs404Not500() throws Exception {
        mockMvc.perform(get("/no/such/path"))
                .andExpect(status().isNotFound());
    }
}
