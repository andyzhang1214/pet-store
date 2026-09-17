package cn.petshop.member.service;

import cn.petshop.member.entity.Member;
import cn.petshop.member.mapper.MemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberMapper memberMapper;

    /**
     * 登录首次建档。并发首次登录（同一 openid 两个请求）会撞 uk_member_openid 唯一键，
     * 这里捕获后重查，保证接口幂等而不是抛 500（对应审查 MINOR-4）。
     */
    public Member findOrCreate(String openid, long storeId) {
        Member exists = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getOpenid, openid));
        if (exists != null) {
            return exists;
        }
        Member member = new Member();
        member.setStoreId(storeId);
        member.setOpenid(openid);
        member.setNickname("铲屎官");
        member.setLevel(1);
        member.setTotalPaid(java.math.BigDecimal.ZERO);
        member.setStatus("0");
        member.setCreatedAt(LocalDateTime.now());
        member.setUpdatedAt(LocalDateTime.now());
        try {
            memberMapper.insert(member);
            return member;
        } catch (DuplicateKeyException e) {
            Member raced = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                    .eq(Member::getOpenid, openid));
            if (raced == null) {
                throw e;
            }
            return raced;
        }
    }

    public Member byId(Long id) {
        return memberMapper.selectById(id);
    }
}
