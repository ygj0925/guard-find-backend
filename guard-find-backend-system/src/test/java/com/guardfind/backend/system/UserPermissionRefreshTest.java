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

package com.guardfind.backend.system;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.guardfind.backend.common.context.UserContext;
import com.guardfind.backend.common.context.UserContextHolder;
import com.guardfind.backend.system.mapper.RoleMenuMapper;
import com.guardfind.backend.system.mapper.UserRoleMapper;
import com.guardfind.backend.system.model.entity.UserRoleDO;
import com.guardfind.backend.system.model.entity.RoleDO;
import com.guardfind.backend.system.model.entity.user.UserDO;
import com.guardfind.backend.system.service.*;
import com.guardfind.backend.system.service.impl.UserRoleServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserPermissionRefreshTest extends AuthorizationTestSupport {
    private RoleService roles;
    private UserPermissionService service;

    @BeforeEach
    void setUp() {
        roles = mock(RoleService.class);
        service = new UserPermissionService(mock(RoleMenuMapper.class));
        ReflectionTestUtils.setField(service, "roleService", roles);
    }

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        UserContextHolder.clearContext();
    }

    @Test
    void refreshWaitsForCommitAndDoesNotReplaceOperatorContext() {
        beginTransaction();
        UserContext operator = new UserContext();
        operator.setId(99L);
        UserContextHolder.setContext(operator, false);
        UserContext target = new UserContext();
        target.setPermissions(Set.of("system:user:delete"));
        SaSession session = mock(SaSession.class);
        when(session.getModel(SaSession.USER, UserContext.class)).thenReturn(target);
        when(roles.listByUserId(7L)).thenReturn(Set.of());
        when(roles.listPermissionByUserId(7L)).thenReturn(Set.of());
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.getSessionByLoginId(7L, false)).thenReturn(session);
            service.refreshUsers(List.of(7L, 7L));
            stp.verifyNoInteractions();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertEquals(Set.of(), target.getPermissions());
            assertEquals(Set.of(), target.getRoleCodes());
            verify(session).set(SaSession.USER, target);
            verify(roles, times(1)).listPermissionByUserId(7L);
            assertSame(operator, UserContextHolder.getContext());
        }
    }

    @Test
    void rollbackLeavesSessionsUntouched() {
        beginTransaction();
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            service.refreshUsers(List.of(7L));
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            stp.verifyNoInteractions();
            verifyNoInteractions(roles);
        }
    }

    @Test
    void offlineUsersDoNotCreateSessions() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            service.refreshUsers(List.of(7L));
            stp.verify(() -> StpUtil.getSessionByLoginId(7L, false));
            verifyNoInteractions(roles);
        }
    }

    @Test
    void refreshFailureInvalidatesSessionInsteadOfLeavingRevokedRights() {
        SaSession session = mock(SaSession.class);
        when(session.getModel(SaSession.USER, UserContext.class)).thenReturn(new UserContext());
        when(roles.listByUserId(7L)).thenThrow(new IllegalStateException("database unavailable"));
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.getSessionByLoginId(7L, false)).thenReturn(session);
            assertThrows(IllegalStateException.class, () -> service.refreshUsers(List.of(7L)));
            stp.verify(() -> StpUtil.logout(7L));
        }
    }

    @Test
    void unbindingCapturesUsersBeforeDeletingTheirBindings() {
        UserRoleMapper mapper = mock(UserRoleMapper.class);
        UserService users = mock(UserService.class);
        UserPermissionService refresher = mock(UserPermissionService.class);
        UserRoleServiceImpl bindings = new UserRoleServiceImpl(mapper);
        ReflectionTestUtils.setField(bindings, "roleService", roles);
        ReflectionTestUtils.setField(bindings, "userService", users);
        ReflectionTestUtils.setField(bindings, "userPermissionService", refresher);
        UserRoleDO binding = new UserRoleDO(7L, 3L);
        when(mapper.selectByIds(List.of(42L))).thenReturn(List.of(binding));
        when(roles.getById(3L)).thenReturn(new RoleDO());
        when(users.getById(7L)).thenReturn(new UserDO());
        bindings.deleteByIds(List.of(42L));
        var order = inOrder(mapper, refresher);
        order.verify(mapper).selectByIds(List.of(42L));
        order.verify(mapper).deleteByIds(List.of(42L));
        order.verify(refresher).refreshUsers(List.of(7L));
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
