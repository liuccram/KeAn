package com.kean.service;

import com.kean.common.PageResult;
import com.kean.vo.TaskVO;

import java.util.Collection;
import java.util.Set;

public interface FavoriteService {

    void add(Long taskId);

    void remove(Long taskId);

    PageResult<TaskVO> listMine(Long page, Long size);

    Set<Long> taskIdsOf(Long userId, Collection<Long> taskIds);

    boolean favorited(Long userId, Long taskId);
}
