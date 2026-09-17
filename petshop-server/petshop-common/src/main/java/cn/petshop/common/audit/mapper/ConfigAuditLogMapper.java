package cn.petshop.common.audit.mapper;

import cn.petshop.common.audit.ConfigAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 配置审计 Mapper。
 *
 * <p>包路径必须落在 {@code cn.petshop.**.mapper} 下，否则 ApplicationContext 启动失败
 * （由 {@code MapperRegistrationTest} 兜底拦截）。</p>
 */
public interface ConfigAuditLogMapper extends BaseMapper<ConfigAuditLog> {
}
