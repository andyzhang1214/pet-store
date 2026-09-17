package cn.petshop.member.controller;

import cn.petshop.common.annotation.RequireRole;
import cn.petshop.common.context.AdminContext;
import cn.petshop.common.context.StoreContext;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.web.R;
import cn.petshop.member.entity.MemberLevel;
import cn.petshop.member.service.AdminMemberLevelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台会员等级配置。全部为店长权限（折扣直接影响收入）。
 *
 * <p>不提供删除：{@code member.level} 是语义外键，删掉一个等级会让这些会员指向不存在的档位。
 * 不用了就把 {@code enabled} 置 0，{@code MemberLevelService.discountFor} 会跳过它。</p>
 */
@RestController
@RequestMapping("/admin/member-levels")
@RequiredArgsConstructor
public class AdminMemberLevelController {

    private final AdminMemberLevelService levelService;

    @GetMapping
    public R<List<MemberLevel>> list() {
        return R.ok(levelService.listLevels(StoreContext.DEFAULT_STORE_ID));
    }

    @PostMapping
    @RequireRole(AdminRole.MANAGER)
    public R<MemberLevel> create(@RequestBody AdminMemberLevelService.LevelCmd cmd) {
        return R.ok(levelService.createLevel(StoreContext.DEFAULT_STORE_ID, cmd, AdminContext.operatorName()));
    }

    @PutMapping("/{id}")
    @RequireRole(AdminRole.MANAGER)
    public R<MemberLevel> update(@PathVariable Long id,
                                @RequestBody AdminMemberLevelService.LevelCmd cmd) {
        return R.ok(levelService.updateLevel(id, cmd, AdminContext.operatorName()));
    }
}
