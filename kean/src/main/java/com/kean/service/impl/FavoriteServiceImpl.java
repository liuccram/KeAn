package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.entity.SubstituteTask;
import com.kean.entity.UserFavorite;
import com.kean.exception.BizException;
import com.kean.mapper.SubstituteTaskMapper;
import com.kean.mapper.UserFavoriteMapper;
import com.kean.security.SecurityUtils;
import com.kean.service.FavoriteService;
import com.kean.service.TaskService;
import com.kean.vo.TaskVO;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class FavoriteServiceImpl implements FavoriteService {

    private static final long DEFAULT_PAGE = 1L;
    private static final long DEFAULT_SIZE = 20L;
    private static final long MAX_SIZE = 50L;

    private final UserFavoriteMapper favoriteMapper;
    private final SubstituteTaskMapper taskMapper;
    private final TaskService taskService;

    public FavoriteServiceImpl(
            UserFavoriteMapper favoriteMapper,
            SubstituteTaskMapper taskMapper,
            @Lazy TaskService taskService
    ) {
        this.favoriteMapper = favoriteMapper;
        this.taskMapper = taskMapper;
        this.taskService = taskService;
    }

    @Override
    @Transactional
    public void add(Long taskId) {
        Long userId = SecurityUtils.currentUserId();
        SubstituteTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        if (Objects.equals(task.getPublisherId(), userId)) {
            throw new BizException(ErrorCode.CANNOT_FAVORITE_OWN);
        }
        Long existed = favoriteMapper.selectCount(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getTaskId, taskId));
        if (existed != null && existed > 0) {
            return;
        }
        UserFavorite row = new UserFavorite();
        row.setUserId(userId);
        row.setTaskId(taskId);
        favoriteMapper.insert(row);
    }

    @Override
    @Transactional
    public void remove(Long taskId) {
        Long userId = SecurityUtils.currentUserId();
        favoriteMapper.delete(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getTaskId, taskId));
    }

    @Override
    public PageResult<TaskVO> listMine(Long page, Long size) {
        Long userId = SecurityUtils.currentUserId();
        long pageNo = page == null || page < 1 ? DEFAULT_PAGE : page;
        long pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Page<UserFavorite> result = favoriteMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<UserFavorite>()
                        .eq(UserFavorite::getUserId, userId)
                        .orderByDesc(UserFavorite::getCreatedAt)
        );
        List<TaskVO> list = result.getRecords().stream().map(item -> {
            try {
                return taskService.detail(item.getTaskId());
            } catch (Exception ex) {
                return null;
            }
        }).filter(item -> item != null).toList();
        return new PageResult<>(list, result.getTotal(), pageNo, pageSize);
    }

    @Override
    public Set<Long> taskIdsOf(Long userId, Collection<Long> taskIds) {
        if (userId == null || taskIds == null || taskIds.isEmpty()) {
            return Set.of();
        }
        List<UserFavorite> rows = favoriteMapper.selectList(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .in(UserFavorite::getTaskId, taskIds));
        Set<Long> ids = new HashSet<>();
        for (UserFavorite row : rows) {
            ids.add(row.getTaskId());
        }
        return ids;
    }

    @Override
    public boolean favorited(Long userId, Long taskId) {
        if (userId == null || taskId == null) {
            return false;
        }
        Long count = favoriteMapper.selectCount(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getTaskId, taskId));
        return count != null && count > 0;
    }
}
