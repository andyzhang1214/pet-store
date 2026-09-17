package cn.petshop.member.mapper;

import cn.petshop.member.entity.Pet;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

public interface PetMapper extends BaseMapper<Pet> {

    /**
     * 忽略逻辑删除标记的批量查询，仅取 id / name。
     * 用途：历史预约、健康档案回显宠物名 —— 宠物被软删除后旧记录仍要能显示名称。
     * 注意：只允许用于「历史展示」，业务校验（新预约、编辑）必须走 selectById 等带逻辑删除过滤的方法。
     */
    @Select("<script>SELECT id, name FROM pet WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Pet> selectNamesIgnoreDeleted(@Param("ids") Collection<Long> ids);
}
