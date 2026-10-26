/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.guardfind.backend.system.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.guardfind.backend.common.context.UserContext;
import com.guardfind.backend.system.mapper.RoleMenuMapper;
import com.guardfind.backend.system.model.entity.RoleMenuDO;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

/** Refresh affected online sessions after the database change has committed. */
@Service
@RequiredArgsConstructor
public class UserPermissionService {
    private final RoleMenuMapper roleMenuMapper;
    @Lazy
    @Resource
    private RoleService roleService;
    @Lazy
    @Resource
    private UserRoleService userRoleService;

    public void refreshMenus(Collection<Long> menuIds) {
        if (menuIds.isEmpty())
            return;
        List<Long> userIds = roleMenuMapper.lambdaQuery()
            .in(RoleMenuDO::getMenuId, menuIds)
            .list()
            .stream()
            .map(RoleMenuDO::getRoleId)
            .distinct()
            .flatMap(roleId -> userRoleService.listUserIdByRoleId(roleId).stream())
            .distinct()
            .toList();
        refreshUsers(userIds);
    }

    public void refreshUsers(Collection<Long> userIds) {
        List<Long> affected = userIds.stream().distinct().toList();
        if (affected.isEmpty())
            return;
        Runnable refresh = () -> {
            RuntimeException failure = null;
            for (Long userId : affected) {
                try {
                    refreshUser(userId);
                } catch (RuntimeException error) {
                    if (failure == null)
                        failure = error;
                    else
                        failure.addSuppressed(error);
                }
            }
            if (failure != null)
                throw failure;
        };
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager
            .isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh.run();
                }
            });
        } else {
            refresh.run();
        }
    }

    private void refreshUser(Long userId) {
        SaSession session = StpUtil.getSessionByLoginId(userId, false);
        if (session == null)
            return;
        UserContext context = session.getModel(SaSession.USER, UserContext.class);
        if (context == null)
            return;
        try {
            context.setRoles(roleService.listByUserId(userId));
            context.setPermissions(roleService.listPermissionByUserId(userId));
            // Do not replace the operator's thread-local context with another user's context.
            session.set(SaSession.USER, context);
        } catch (RuntimeException error) {
            // A committed revocation must not leave a usable session with stale permissions.
            StpUtil.logout(userId);
            throw error;
        }
    }
}
