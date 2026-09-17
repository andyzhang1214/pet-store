package cn.petshop;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper 注册回归测试。
 *
 * <h3>为什么需要这个测试</h3>
 * {@code PetshopApplication} 上是 {@code @MapperScan("cn.petshop.**.mapper")} —— 一个**按包名匹配**的约定。
 * 于是「新建一个 Mapper 但放错包」不会编译报错、不会启动报错（在没有消费者时甚至完全静默），
 * 只在某个 Service 注入它的那一刻炸成 {@code APPLICATION FAILED TO START}。
 *
 * <p>真实事故：{@code AdminUserMapper} 被放在 {@code cn.petshop.admin.auth} 而不是
 * {@code cn.petshop.admin.auth.mapper}，导致 **整个测试套件 80 个用例全灭**，
 * 而报错信息只显示 "ApplicationContext failure threshold exceeded"，根因被折叠在日志里，
 * 排查成本极高。</p>
 *
 * <h3>本测试做什么</h3>
 * 扫全部 {@code cn.petshop} 下的 {@code BaseMapper} 子接口，逐个要求容器里存在对应 Bean。
 * 只要有人再把 Mapper 放到 {@code **.mapper} 之外，这个测试会**指名道姓**说出是哪一个。
 */
@SpringBootTest
@ActiveProfiles("test")
class MapperRegistrationTest {

    /** 必须与 PetshopApplication 上的 @MapperScan 保持一致 */
    private static final String SCAN_BASE_PACKAGE = "cn.petshop";
    private static final String MAPPER_PACKAGE_PATTERN = "cn.petshop.**.mapper";

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void everyBaseMapperInterfaceIsRegisteredAsBean() {
        List<Class<?>> mapperInterfaces = findMapperInterfaces();
        assertTrue(mapperInterfaces.size() >= 10,
                "扫描到的 Mapper 数量异常（" + mapperInterfaces.size() + "），扫描逻辑可能已失效");

        List<String> missing = new ArrayList<>();
        for (Class<?> mapper : mapperInterfaces) {
            String[] names = applicationContext.getBeanNamesForType(mapper);
            if (names.length == 0) {
                missing.add(mapper.getName());
            }
        }

        assertTrue(missing.isEmpty(),
                "以下 Mapper 存在但没有注册成 Bean，几乎肯定是包路径不在 [" + MAPPER_PACKAGE_PATTERN + "] 下，"
                        + "请移动到对应的 mapper 子包：\n  - " + String.join("\n  - ", missing));
    }

    /**
     * 找出所有 BaseMapper 子接口。
     *
     * <p>两个坑都得绕开：</p>
     * <ol>
     *   <li>默认过滤器只扫「具体类」，接口会被静默丢弃 —— 所以关掉默认过滤器，自己加
     *       {@link AssignableTypeFilter}。</li>
     *   <li>即便过滤器命中了接口，{@code ClassPathScanningCandidateComponentProvider.isCandidateComponent}
     *       仍会以 {@code metadata.isConcrete()} 再把接口拒掉。必须覆写它显式放行接口，
     *       否则结果是「扫到 0 个」而不报错 —— 一个只会绿灯的假测试。</li>
     * </ol>
     */
    private List<Class<?>> findMapperInterfaces() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        // 本项目要检查的目标全是接口，放行接口、排除具体类
                        return beanDefinition.getMetadata().isInterface();
                    }
                };
        provider.addIncludeFilter(new AssignableTypeFilter(BaseMapper.class));

        Set<String> classNames = new TreeSet<>();
        for (BeanDefinition bd : provider.findCandidateComponents(SCAN_BASE_PACKAGE)) {
            classNames.add(bd.getBeanClassName());
        }

        List<Class<?>> result = new ArrayList<>();
        for (String name : classNames) {
            try {
                Class<?> clazz = Class.forName(name);
                if (!clazz.equals(BaseMapper.class)) {
                    result.add(clazz);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到的类无法加载：" + name, e);
            }
        }
        return result;
    }
}
