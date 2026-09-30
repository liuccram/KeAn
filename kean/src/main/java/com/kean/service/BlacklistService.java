package com.kean.service;

import com.kean.vo.BlacklistItemVO;

import java.util.List;
import java.util.Set;

public interface BlacklistService {

    List<BlacklistItemVO> listMine();

    void block(Long blockedUserId);

    void unblock(Long blockedUserId);

    boolean blockedEitherWay(Long userId, Long otherUserId);

    Set<Long> relatedUserIds(Long userId);

    void assertCanInteract(Long userId, Long otherUserId);
}
