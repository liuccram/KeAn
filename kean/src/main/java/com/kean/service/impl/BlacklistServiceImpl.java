package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kean.common.ErrorCode;
import com.kean.entity.SysUser;
import com.kean.entity.UserBlacklist;
import com.kean.enums.UserRole;
import com.kean.exception.BizException;
import com.kean.mapper.CampusMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.mapper.UserBlacklistMapper;
import com.kean.security.SecurityUtils;
import com.kean.service.BlacklistService;
import com.kean.utils.FileUrls;
import com.kean.vo.BlacklistItemVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class BlacklistServiceImpl implements BlacklistService {

    private final UserBlacklistMapper blacklistMapper;
    private final SysUserMapper sysUserMapper;
    private final CampusMapper campusMapper;

    public BlacklistServiceImpl(
            UserBlacklistMapper blacklistMapper,
            SysUserMapper sysUserMapper,
            CampusMapper campusMapper
    ) {
        this.blacklistMapper = blacklistMapper;
        this.sysUserMapper = sysUserMapper;
        this.campusMapper = campusMapper;
    }

    @Override
    public List<BlacklistItemVO> listMine() {
        Long userId = SecurityUtils.currentUserId();
        List<UserBlacklist> rows = blacklistMapper.selectList(new LambdaQueryWrapper<UserBlacklist>()
                .eq(UserBlacklist::getUserId, userId)
                .orderByDesc(UserBlacklist::getCreatedAt));
        Set<Long> campusIds = new HashSet<>();
        Map<Long, SysUser> users = new HashMap<>();
        for (UserBlacklist row : rows) {
            SysUser user = sysUserMapper.selectById(row.getBlockedUserId());
            if (user != null) {
                users.put(user.getId(), user);
                if (user.getCampusId() != null) {
                    campusIds.add(user.getCampusId());
                }
            }
        }
        Map<Long, String> campusNames = new HashMap<>();
        if (!campusIds.isEmpty()) {
            campusMapper.selectByIds(campusIds).forEach(campus -> campusNames.put(campus.getId(), campus.getName()));
        }
        return rows.stream().map(row -> {
            SysUser user = users.get(row.getBlockedUserId());
            return new BlacklistItemVO(
                    row.getId(),
                    row.getBlockedUserId(),
                    user == null ? "同学" : user.getNickname(),
                    user == null ? null : FileUrls.of(user.getAvatarUrl()),
                    user == null ? null : campusNames.get(user.getCampusId()),
                    row.getCreatedAt()
            );
        }).toList();
    }

    @Override
    @Transactional
    public void block(Long blockedUserId) {
        Long userId = SecurityUtils.currentUserId();
        if (blockedUserId == null || Objects.equals(userId, blockedUserId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能拉黑自己");
        }
        SysUser target = sysUserMapper.selectById(blockedUserId);
        if (target == null || !UserRole.USER.name().equals(target.getRole())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户不存在");
        }
        Long existed = blacklistMapper.selectCount(new LambdaQueryWrapper<UserBlacklist>()
                .eq(UserBlacklist::getUserId, userId)
                .eq(UserBlacklist::getBlockedUserId, blockedUserId));
        if (existed != null && existed > 0) {
            throw new BizException(ErrorCode.ALREADY_BLOCKED);
        }
        UserBlacklist row = new UserBlacklist();
        row.setUserId(userId);
        row.setBlockedUserId(blockedUserId);
        blacklistMapper.insert(row);
    }

    @Override
    @Transactional
    public void unblock(Long blockedUserId) {
        Long userId = SecurityUtils.currentUserId();
        UserBlacklist row = blacklistMapper.selectOne(new LambdaQueryWrapper<UserBlacklist>()
                .eq(UserBlacklist::getUserId, userId)
                .eq(UserBlacklist::getBlockedUserId, blockedUserId));
        if (row == null) {
            throw new BizException(ErrorCode.BLACKLIST_NOT_FOUND);
        }
        blacklistMapper.deleteById(row.getId());
    }

    @Override
    public boolean blockedEitherWay(Long userId, Long otherUserId) {
        if (userId == null || otherUserId == null || Objects.equals(userId, otherUserId)) {
            return false;
        }
        Long count = blacklistMapper.selectCount(new LambdaQueryWrapper<UserBlacklist>()
                .and(w -> w
                        .eq(UserBlacklist::getUserId, userId).eq(UserBlacklist::getBlockedUserId, otherUserId)
                        .or()
                        .eq(UserBlacklist::getUserId, otherUserId).eq(UserBlacklist::getBlockedUserId, userId)));
        return count != null && count > 0;
    }

    @Override
    public Set<Long> relatedUserIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        if (userId == null) {
            return ids;
        }
        List<UserBlacklist> mine = blacklistMapper.selectList(new LambdaQueryWrapper<UserBlacklist>()
                .eq(UserBlacklist::getUserId, userId));
        for (UserBlacklist row : mine) {
            ids.add(row.getBlockedUserId());
        }
        List<UserBlacklist> against = blacklistMapper.selectList(new LambdaQueryWrapper<UserBlacklist>()
                .eq(UserBlacklist::getBlockedUserId, userId));
        for (UserBlacklist row : against) {
            ids.add(row.getUserId());
        }
        return ids;
    }

    @Override
    public void assertCanInteract(Long userId, Long otherUserId) {
        if (blockedEitherWay(userId, otherUserId)) {
            throw new BizException(ErrorCode.USER_BLOCKED);
        }
    }
}
