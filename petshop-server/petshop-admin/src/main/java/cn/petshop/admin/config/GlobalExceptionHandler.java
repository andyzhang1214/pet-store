package cn.petshop.admin.config;

import cn.petshop.common.web.BizException;
import cn.petshop.common.web.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        return R.fail(e.getErrorCode().getCode(), e.getMessage());
    }

    /**
     * 静态资源 / 无映射路径不存在 → 404。
     * 之前落到兜底 Exception 处理器被吞成 500（如访问 /manage、/manage/ 时），
     * 掩盖了真实状态码，排查误导。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("resource not found: {}", e.getResourcePath());
        return R.fail(404, "页面或资源不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleOther(Exception e) {
        log.error("unexpected error", e);
        return R.fail(500, "服务开小差了，请稍后重试");
    }
}
