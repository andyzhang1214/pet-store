package cn.petshop.admin.config;

import cn.petshop.admin.interceptor.AdminTokenInterceptor;
import cn.petshop.admin.interceptor.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final AdminTokenInterceptor adminTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/login");
        registry.addInterceptor(adminTokenInterceptor)
                .addPathPatterns("/admin/**")
                // 登录本身当然不能要求已登录。只排这一个路径：
                // /admin/auth 下的 /me 与 /password 需要知道"你是谁"，必须留在鉴权内。
                .excludePathPatterns("/admin/auth/login");
    }
}
