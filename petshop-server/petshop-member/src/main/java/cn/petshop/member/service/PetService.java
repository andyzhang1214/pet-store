package cn.petshop.member.service;

import cn.petshop.common.context.StoreContext;

import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.mapper.PetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PetService {

    private final PetMapper petMapper;

    public Pet create(Long memberId, Pet pet) {
        validateSpecFields(pet);
        pet.setId(null);
        pet.setMemberId(memberId);
        pet.setStoreId(StoreContext.DEFAULT_STORE_ID);
        pet.setCreatedAt(LocalDateTime.now());
        pet.setUpdatedAt(LocalDateTime.now());
        petMapper.insert(pet);
        return pet;
    }

    public List<Pet> listByMember(Long memberId) {
        return petMapper.selectList(new LambdaQueryWrapper<Pet>()
                .eq(Pet::getMemberId, memberId)
                .orderByAsc(Pet::getId));
    }

    public Pet getOwnedPet(Long memberId, Long petId) {
        Pet pet = petMapper.selectById(petId);
        if (pet == null || !pet.getMemberId().equals(memberId)) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "宠物不存在");
        }
        return pet;
    }

    public Pet update(Long memberId, Long petId, Pet patch) {
        Pet pet = getOwnedPet(memberId, petId);
        if (patch.getName() != null) {
            pet.setName(patch.getName());
        }
        if (patch.getType() != null || patch.getSize() != null || patch.getHair() != null) {
            pet.setType(patch.getType() != null ? patch.getType() : pet.getType());
            pet.setSize(patch.getSize() != null ? patch.getSize() : pet.getSize());
            pet.setHair(patch.getHair() != null ? patch.getHair() : pet.getHair());
            validateSpecFields(pet);
        }
        if (patch.getGender() != null) {
            pet.setGender(patch.getGender());
        }
        if (patch.getBirthday() != null) {
            pet.setBirthday(patch.getBirthday());
        }
        if (patch.getNeutered() != null) {
            pet.setNeutered(patch.getNeutered());
        }
        if (patch.getWeight() != null) {
            pet.setWeight(patch.getWeight());
        }
        pet.setUpdatedAt(LocalDateTime.now());
        petMapper.updateById(pet);
        return pet;
    }

    /**
     * 逻辑删除（@TableLogic -> UPDATE pet SET deleted = 1）。
     * 不再物理删除：历史预约与健康档案仍需引用该宠物，物理删除会导致
     * petName 变 null、旧预约无法核销结算（对应审查 M-7）。
     */
    public void delete(Long memberId, Long petId) {
        Pet pet = getOwnedPet(memberId, petId);
        petMapper.deleteById(pet.getId());
    }

    /** 批量取宠物名（含已逻辑删除的宠物），供历史记录回显。 */
    public Map<Long, String> namesIgnoreDeleted(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return Map.of();
        }
        return petMapper.selectNamesIgnoreDeleted(petIds).stream()
                .collect(Collectors.toMap(Pet::getId, Pet::getName, (a, b) -> a));
    }

    private void validateSpecFields(Pet pet) {
        if (pet.getType() == null || pet.getHair() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "宠物类型与毛长为规格匹配必填项");
        }
    }
}
