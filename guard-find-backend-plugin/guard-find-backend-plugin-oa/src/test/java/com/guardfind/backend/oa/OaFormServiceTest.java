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

package com.guardfind.backend.oa;

import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaFormUpdateReq;
import com.guardfind.backend.oa.repository.OaFormRepository;
import com.guardfind.backend.oa.service.OaFormService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import top.continew.starter.core.exception.BusinessException;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 9.8 表单草稿与不可变版本：发布快照、指针推进、停用、租户隔离与版本选项。
 */
class OaFormServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private static final String SCHEMA = """
        {"version":1,"fields":[
          {"id":"f1","key":"amount","label":"金额","type":"number","required":true},
          {"id":"f2","key":"type","label":"类型","type":"radio","options":[{"label":"事假","value":"personal"}]}
        ]}
        """;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:oa-form-" + DB_SEQ
                .incrementAndGet() + ";DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=");
    }

    @Test
    void createsDraftAndRejectsDuplicateFormKey() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaFormResp created = fixture.service.create(101, 7, new OaFormCreateReq("leave_form", "请假单", null, null));
            assertThat(created.status()).isEqualTo("DRAFT");
            assertThat(created.currentVersion()).isZero();
            assertThat(created.version()).isZero();
            assertThat(created.schemaJson()).contains("\"fields\":[]");
            assertThat(created.schemaDigest()).hasSize(64);

            assertThatThrownBy(() -> fixture.service
                .create(101, 7, new OaFormCreateReq("leave_form", "请假单2", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("表单标识已存在");

            assertThat(fixture.service.page(101, "请假", null, 1, 10)).hasSize(1);
            assertThat(fixture.service.page(202, null, null, 1, 10)).isEmpty();
        });
    }

    @Test
    void updatesDraftAndRejectsStaleVersion() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaFormResp created = fixture.service.create(101, 7, new OaFormCreateReq("leave_form", "请假单", null, null));
            OaFormResp updated = fixture.service.update(101, 7, created
                .id(), new OaFormUpdateReq("请假申请", "说明", SCHEMA, created.version()));
            assertThat(updated.name()).isEqualTo("请假申请");
            assertThat(updated.schemaJson()).contains("\"amount\"");
            assertThat(updated.version()).isEqualTo(1);

            assertThatThrownBy(() -> fixture.service.update(101, 7, created
                .id(), new OaFormUpdateReq("旧数据", null, SCHEMA, created.version())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他人修改");

            OaFormResp keptSchema = fixture.service.update(101, 7, created
                .id(), new OaFormUpdateReq("请假申请", null, null, updated.version()));
            assertThat(keptSchema.schemaJson()).contains("\"amount\"");
        });
    }

    @Test
    void rejectsInvalidSchemaWithoutWritingVersionOrPointer() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaFormResp created = fixture.service.create(101, 7, new OaFormCreateReq("leave_form", "请假单", null, null));

            OaFormPublishResp result = fixture.service.publish(101, 7, created.id());

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).containsExactly("表单至少需要一个字段才能发布");
            assertThat(fixture.service.versions(101, created.id())).isEmpty();
            OaFormResp unchanged = fixture.service.detail(101, created.id());
            assertThat(unchanged.status()).isEqualTo("DRAFT");
            assertThat(unchanged.currentVersion()).isZero();
            assertThat(unchanged.currentVersionId()).isNull();
        });
    }

    @Test
    void publishesImmutableSnapshotAndAdvancesPointer() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaFormResp created = fixture.service.create(101, 7, new OaFormCreateReq("leave_form", "请假单", null, SCHEMA));

            OaFormPublishResp published = fixture.service.publish(101, 7, created.id());
            assertThat(published.valid()).isTrue();
            assertThat(published.version()).isEqualTo(1);
            assertThat(published.fieldCount()).isEqualTo(2);
            assertThat(published.schemaDigest()).hasSize(64);

            OaFormResp detail = fixture.service.detail(101, created.id());
            assertThat(detail.status()).isEqualTo("PUBLISHED");
            assertThat(detail.currentVersion()).isEqualTo(1);
            assertThat(detail.currentVersionId()).isEqualTo(published.versionId());

            var versions = fixture.service.versions(101, created.id());
            assertThat(versions).hasSize(1);
            assertThat(versions.get(0).version()).isEqualTo(1);
            assertThat(versions.get(0).fieldCount()).isEqualTo(2);
            assertThat(versions.get(0).schemaJson()).isNull();
            assertThat(versions.get(0).schemaDigest()).isEqualTo(published.schemaDigest());

            fixture.service.update(101, 7, created.id(), new OaFormUpdateReq("请假单", null, """
                {"version":1,"fields":[{"id":"f1","key":"name","label":"姓名","type":"text"}]}
                """, detail.version()));
            OaFormPublishResp second = fixture.service.publish(101, 7, created.id());
            assertThat(second.version()).isEqualTo(2);
            assertThat(fixture.service.versions(101, created.id())).hasSize(2);

            String firstSnapshot = fixture.jdbc.queryForObject("""
                SELECT schema_json FROM oa_form_version WHERE tenant_id = 101 AND form_id = ? AND version = 1
                """, String.class, created.id());
            assertThat(firstSnapshot).isEqualTo(SCHEMA);
        });
    }

    @Test
    void disablesFormAndStopsOfferingVersions() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaFormResp created = fixture.service.create(101, 7, new OaFormCreateReq("leave_form", "请假单", null, SCHEMA));
            fixture.service.publish(101, 7, created.id());
            assertThat(fixture.service.versionOptions(101)).hasSize(1);

            OaFormResp disabled = fixture.service.disable(101, 7, created.id());
            assertThat(disabled.status()).isEqualTo("DISABLED");
            assertThat(fixture.service.versionOptions(101)).isEmpty();
            assertThat(fixture.service.versions(101, created.id())).hasSize(1);
        });
    }

    @Test
    void versionOptionsArePublishedOnlyAndTenantScoped() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaFormResp draft = fixture.service.create(101, 7, new OaFormCreateReq("draft_form", "草稿", null, null));
            OaFormResp published = fixture.service
                .create(101, 7, new OaFormCreateReq("leave_form", "请假单", null, SCHEMA));
            fixture.service.publish(101, 7, published.id());

            var options = fixture.service.versionOptions(101);
            assertThat(options).hasSize(1);
            assertThat(options.get(0).formId()).isEqualTo(published.id());
            assertThat(options.get(0).formKey()).isEqualTo("leave_form");
            assertThat(options.get(0).version()).isEqualTo(1);
            assertThat(options).noneMatch(option -> option.formId() == draft.id());

            assertThat(fixture.service.versionOptions(202)).isEmpty();
            assertThatThrownBy(() -> fixture.service.detail(202, published.id())).isInstanceOf(BusinessException.class)
                .hasMessageContaining("表单不存在");
        });
    }

    @Test
    void readsPublishedVersionDetailForRenderingAndKeepsTenantIsolation() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaFormResp created = fixture.service.create(101, 7, new OaFormCreateReq("leave_form", "请假单", null, SCHEMA));
            OaFormPublishResp published = fixture.service.publish(101, 7, created.id());

            var detail = fixture.service.versionDetail(101, published.versionId());
            assertThat(detail.formId()).isEqualTo(created.id());
            assertThat(detail.formKey()).isEqualTo("leave_form");
            assertThat(detail.name()).isEqualTo("请假单");
            assertThat(detail.status()).isEqualTo("PUBLISHED");
            assertThat(detail.version()).isEqualTo(1);
            assertThat(detail.schemaJson()).isEqualTo(SCHEMA);
            assertThat(detail.schemaDigest()).isEqualTo(published.schemaDigest());
            assertThat(detail.fieldCount()).isEqualTo(2);

            assertThatThrownBy(() -> fixture.service.versionDetail(202, published.versionId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("表单版本不存在");
            assertThatThrownBy(() -> fixture.service.versionDetail(101, published.versionId() + 999))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("表单版本不存在");
        });
    }

    private static Fixture fixture(ConfigurableApplicationContext context) {
        DataSource dataSource = context.getBean(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTables(jdbc);
        OaFormRepository repository = new OaFormRepository(jdbc);
        OaFormService service = new OaFormService(repository, new OaFormSchemaValidator(), context
            .getBean(PlatformTransactionManager.class));
        return new Fixture(service, jdbc);
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE oa_form (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
              tenant_id BIGINT NOT NULL,
              form_key VARCHAR(64) NOT NULL,
              name VARCHAR(100) NOT NULL,
              description VARCHAR(500),
              draft_schema_json CLOB,
              draft_schema_digest VARCHAR(64),
              status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
              current_version INT NOT NULL DEFAULT 0,
              current_version_id BIGINT,
              version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL,
              create_time TIMESTAMP NOT NULL,
              update_user BIGINT,
              update_time TIMESTAMP,
              deleted BIGINT NOT NULL DEFAULT 0,
              CONSTRAINT uk_oa_form_key UNIQUE (tenant_id, form_key, deleted)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_form_version (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
              tenant_id BIGINT NOT NULL,
              form_id BIGINT NOT NULL,
              version INT NOT NULL,
              schema_json CLOB NOT NULL,
              schema_digest VARCHAR(64) NOT NULL,
              field_count INT NOT NULL DEFAULT 0,
              published_by BIGINT NOT NULL,
              publish_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_oa_form_version UNIQUE (tenant_id, form_id, version)
            )
            """);
    }

    private record Fixture(OaFormService service, JdbcTemplate jdbc) {
    }
}
