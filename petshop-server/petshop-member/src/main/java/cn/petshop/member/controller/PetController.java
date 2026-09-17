package cn.petshop.member.controller;

import cn.petshop.common.context.MemberContext;
import cn.petshop.common.web.R;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.service.HealthArchiveService;
import cn.petshop.member.service.PetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pets")
@RequiredArgsConstructor
public class PetController {

    private final PetService petService;
    private final HealthArchiveService healthArchiveService;

    @GetMapping
    public R<List<Pet>> list() {
        return R.ok(petService.listByMember(MemberContext.require()));
    }

    @PostMapping
    public R<Pet> create(@RequestBody Pet pet) {
        return R.ok(petService.create(MemberContext.require(), pet));
    }

    @PutMapping("/{id}")
    public R<Pet> update(@PathVariable Long id, @RequestBody Pet patch) {
        return R.ok(petService.update(MemberContext.require(), id, patch));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        petService.delete(MemberContext.require(), id);
        return R.ok();
    }

    @GetMapping("/{id}/health")
    public R<HealthArchiveService.ArchiveVO> health(@PathVariable Long id) {
        return R.ok(healthArchiveService.archive(MemberContext.require(), id));
    }
}
