package cn.petshop.admin.stats;

import cn.petshop.common.web.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台数据统计。/admin/** 由 petshop-admin 的 AdminTokenInterceptor 统一鉴权；
 * 只读报表，登录即可访问（店员日常也需要看今日预约 / 营收），不加 RequireRole。
 */
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final StatisticsService statisticsService;

    @GetMapping("/overview")
    public R<StatisticsService.Overview> overview(@RequestParam(defaultValue = "7") int days) {
        // days 是展示窗口不是安全参数，夹到合理区间即可，不必报错
        if (days < 1) days = 1;
        if (days > 30) days = 30;
        return R.ok(statisticsService.overview(days));
    }
}
