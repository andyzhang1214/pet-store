package cn.petshop.admin.auth.mapper;

import cn.petshop.admin.auth.AdminUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 后台账号 Mapper。
 *
 * <p>包路径必须落在 {@code cn.petshop.**.mapper} 下，才能被
 * {@code PetshopApplication} 上的 {@code @MapperScan} 扫描到；
 * 放到 {@code admin.auth} 根包会导致 ApplicationContext 启动失败。</p>
 */
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
