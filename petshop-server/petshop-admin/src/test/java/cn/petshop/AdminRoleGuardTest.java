package cn.petshop;

import cn.petshop.admin.auth.AdminAuthService;
import cn.petshop.admin.auth.AdminUser;
import cn.petshop.common.audit.ConfigAuditLog;
import cn.petshop.common.audit.ConfigAuditService;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.util.JwtUtil;
import cn.petshop.mall.service.AdminCatalogService;
import cn.petshop.mall.service.AdminGoodsVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台角色守卫的 HTTP 级回归：注解 {@code @RequireRole} 到底有没有生效。
 *
 * <h3>为什么不能只测 Service 层</h3>
 * 权限校验发生在 {@code AdminTokenInterceptor.preHandle} 里，Service 层根本看不到它。
 * 只测 Service 的话，"注解写错值"和"拦截器没登记这条路径"这两类错误一个都测不出来 ——
 * 而它们的表现恰恰是"接口看起来正常，谁都能调"。
 *
 * <h3>顺带锁住「操作人不可伪造」</h3>
 * 请求体里塞 {@code operator} 字段，断言落库的操作人仍是令牌对应的账号。
 * 这是这次账号体系改造的核心收益，必须有断言守着，否则很容易被某次"顺手支持一下"改回去。
 *
 * <h3>⚠️ 为什么这个类必须自带一个独立数据库</h3>
 * {@code @AutoConfigureMockMvc} 会让 Spring 生成一个<b>与普通 @SpringBootTest 不同</b>的
 * 上下文缓存键，于是同一个 JVM 里出现两个 ApplicationContext。
 * 而 {@code application-test.yml} 里的库是 {@code jdbc:h2:mem:petshop;DB_CLOSE_DELAY=-1}
 * —— 名字固定、进程内常驻，第二个上下文启动时 {@code spring.sql.init.mode=always}
 * 会把 {@code db/01_schema.sql} <b>再跑一遍</b>。
 * 表都写了 {@code CREATE TABLE IF NOT EXISTS} 所以无事，但索引是裸的 {@code CREATE INDEX}
 * （MySQL 不支持 {@code CREATE INDEX IF NOT EXISTS}，不能简单地加上去），
 * 第二次执行直接 `Index "IDX_LEDGER_MEMBER" already exists` 而启动失败。
 *
 * <p>症状极难定位：单独跑这个类是绿的，全量跑就 8 个用例一起报
 * {@code ApplicationContext failure threshold exceeded}，真正的根因被折叠在日志里。
 * 解法就是下面这行 {@code @TestPropertySource} —— 给这个上下文一个自己的库名。</p>
 *
 * <p><b>给后来者</b>：任何需要让 Spring 换一个上下文缓存键的测试（MockMvc、TestRestTemplate、
 * {@code @MockBean}、{@code @TestPropertySource}……），都要像这里一样显式指定独立的 H2 库名，
 * 否则就会重演上面这一幕。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:petshop-admin-guard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class AdminRoleGuardTest {

    private static final String JWT_SECRET = "test-secret";
    private static final String SHARED_TOKEN = "test-admin-token";
    private static final long STORE = 1L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AdminAuthService adminAuthService;
    @Autowired
    private AdminCatalogService catalogService;
    @Autowired
    private ConfigAuditService auditService;

    /** 造一个已改过初始口令、可直接使用的店员账号，返回其后台令牌。 */
    private String staffToken() {
        String username = "staff" + (System.nanoTime() % 100000);
        AdminUser staff = adminAuthService.createAccount(username, "王店员", AdminRole.STAFF,
                "staff-Init1", STORE);
        // 引导创建的账号 must_change_pwd=1，会被拦截器挡在门外；改一次口令清除该标记
        adminAuthService.changePassword(staff.getId(), "staff-Init1", "staff-Changed1");
        return JwtUtil.signAdmin(staff.getId(), 3_600_000L, JWT_SECRET);
    }

    private Long newSkuId(String tag) {
        AdminCatalogService.GoodsCmd cmd = new AdminCatalogService.GoodsCmd();
        cmd.setName(tag + "-" + System.nanoTime());
        cmd.setCategory("SNACK");
        AdminGoodsVO goods = catalogService.createGoods(STORE, cmd, "初始化");
        AdminCatalogService.SkuCmd sku = new AdminCatalogService.SkuCmd();
        sku.setName("规格");
        sku.setPrice(new BigDecimal("40.00"));
        sku.setStock(10);
        return catalogService.addSku(goods.getId(), sku, "初始化").getSkus().get(0).getId();
    }

    // ------------------------------------------------------------ 认证

    @Test
    void adminEndpointRejectsMissingCredential() throws Exception {
        Long skuId = newSkuId("无凭证");
        mockMvc.perform(post("/admin/skus/" + skuId + "/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":1,\"remark\":\"测试\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongSharedTokenIsRejected() throws Exception {
        Long skuId = newSkuId("错令牌");
        mockMvc.perform(post("/admin/skus/" + skuId + "/stock")
                        .header("X-Admin-Token", "not-the-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":1,\"remark\":\"测试\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ 角色

    @Test
    void staffCanAdjustStock() throws Exception {
        Long skuId = newSkuId("店员盘点");
        String token = staffToken();

        mockMvc.perform(post("/admin/skus/" + skuId + "/stock")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":3,\"remark\":\"到店清点\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockAfter").value(13));
    }

    @Test
    void staffCannotChangeSkuPrice() throws Exception {
        Long skuId = newSkuId("店员改价");
        String token = staffToken();

        mockMvc.perform(put("/admin/skus/" + skuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":1.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1025));
    }

    @Test
    void staffCannotChangeStoreConfig() throws Exception {
        String token = staffToken();
        mockMvc.perform(put("/admin/store-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotCapacity\":99}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1025));
    }

    @Test
    void staffCannotTouchRechargeTiers() throws Exception {
        String token = staffToken();
        mockMvc.perform(post("/admin/recharge-tiers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1.00,\"gift\":1.00}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanChangeSkuPrice() throws Exception {
        Long skuId = newSkuId("店长改价");
        // 共享令牌在 dev/test 下等价于店长，用于本地联调
        mockMvc.perform(put("/admin/skus/" + skuId)
                        .header("X-Admin-Token", SHARED_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":55.00}"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------- 操作人由服务端注入，不可伪造

    @Test
    void operatorIsTakenFromTokenNotFromRequestBody() throws Exception {
        Long skuId = newSkuId("伪造操作人");
        String token = staffToken();

        mockMvc.perform(post("/admin/skus/" + skuId + "/stock")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 请求体里塞一个"假操作人"，它不应该是落库的值
                        .content("{\"delta\":2,\"remark\":\"冒名测试\",\"operator\":\"张店长\",\"operatorName\":\"张店长\"}"))
                .andExpect(status().isOk());

        List<ConfigAuditLog> logs = auditService.list(ConfigAuditLog.TARGET_SKU, skuId, 20);
        ConfigAuditLog stockRow = logs.stream()
                .filter(l -> "库存".equals(l.getFieldName()))
                .findFirst().orElse(null);
        assertNotNull(stockRow, "盘点必须留下审计行");
        assertEquals("王店员", stockRow.getOperator(),
                "操作人必须来自令牌解析出的账号，而不是请求体 —— 否则留痕只是「让操作者自己声明自己是谁」");
    }
}
