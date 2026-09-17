package cn.petshop.admin.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 后台静态页入口。
 * 静态资源只认完整文件名（/manage/index.html），裸路径 /manage、/manage/、/ 没有映射，
 * 之前会落到静态资源处理器抛 NoResourceFoundException（一度还被吞成 500）。
 * 这里统一 302 到后台首页，保证直接输入 localhost:端口 或 localhost:端口/manage 也能打开后台。
 */
@Controller
public class HomeController {

    @GetMapping({"/", "/manage", "/manage/"})
    public String manage() {
        return "redirect:/manage/index.html";
    }
}
