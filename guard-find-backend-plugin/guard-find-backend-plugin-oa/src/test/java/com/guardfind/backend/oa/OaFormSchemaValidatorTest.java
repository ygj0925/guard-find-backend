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
import com.guardfind.backend.oa.model.OaFormValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9.8 表单 schema 校验：八类字段、选项约束、重复 key 与发布前空表单拦截。
 */
class OaFormSchemaValidatorTest {

    private final OaFormSchemaValidator validator = new OaFormSchemaValidator();

    @Test
    void acceptsAllEightFieldTypes() {
        String schema = """
            {"version":1,"fields":[
              {"id":"f1","key":"name","label":"姓名","type":"text","required":true},
              {"id":"f2","key":"reason","label":"事由","type":"textarea"},
              {"id":"f3","key":"amount","label":"金额","type":"number"},
              {"id":"f4","key":"day","label":"日期","type":"date"},
              {"id":"f5","key":"type","label":"类型","type":"radio","options":[{"label":"事假","value":"personal"},{"label":"病假","value":"sick"}]},
              {"id":"f6","key":"tags","label":"标签","type":"checkbox","options":[{"label":"紧急","value":"urgent"}]},
              {"id":"f7","key":"files","label":"附件","type":"attachment"},
              {"id":"f8","key":"owner","label":"负责人","type":"person"}
            ]}
            """;
        OaFormValidationResult result = validator.validate(schema, true);
        assertThat(result.valid()).isTrue();
        assertThat(result.fieldCount()).isEqualTo(8);
        assertThat(result.digest()).hasSize(64);
    }

    @Test
    void rejectsUnsupportedType() {
        String schema = """
            {"fields":[{"id":"f1","key":"sign","label":"签名","type":"signature"}]}
            """;
        OaFormValidationResult result = validator.validate(schema, true);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("类型不支持"));
    }

    @Test
    void rejectsDuplicateKeyAndId() {
        String schema = """
            {"fields":[
              {"id":"f1","key":"amount","label":"金额","type":"number"},
              {"id":"f1","key":"amount","label":"金额2","type":"number"}
            ]}
            """;
        OaFormValidationResult result = validator.validate(schema, true);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("字段 key 重复"));
        assertThat(result.errors()).anyMatch(error -> error.contains("字段 id 重复"));
    }

    @Test
    void rejectsOptionFieldsWithoutOptionsAndDuplicatedValues() {
        String missing = """
            {"fields":[{"id":"f1","key":"type","label":"类型","type":"radio"}]}
            """;
        OaFormValidationResult missingResult = validator.validate(missing, true);
        assertThat(missingResult.valid()).isFalse();
        assertThat(missingResult.errors()).anyMatch(error -> error.contains("至少需要一个选项"));

        String duplicated = """
            {"fields":[{"id":"f1","key":"type","label":"类型","type":"checkbox","options":[
              {"label":"A","value":"same"},{"label":"B","value":"same"}
            ]}]}
            """;
        OaFormValidationResult duplicatedResult = validator.validate(duplicated, true);
        assertThat(duplicatedResult.valid()).isFalse();
        assertThat(duplicatedResult.errors()).anyMatch(error -> error.contains("选项值重复"));
    }

    @Test
    void allowsEmptyDraftButRejectsEmptyPublish() {
        String empty = """
            {"version":1,"fields":[]}
            """;
        assertThat(validator.validate(empty, false).valid()).isTrue();
        OaFormValidationResult publish = validator.validate(empty, true);
        assertThat(publish.valid()).isFalse();
        assertThat(publish.errors()).containsExactly("表单至少需要一个字段才能发布");
    }

    @Test
    void rejectsMalformedJsonAndMissingFields() {
        assertThat(validator.validate("{not-json", true).valid()).isFalse();
        assertThat(validator.validate("[]", true).valid()).isFalse();
        assertThat(validator.validate("{\"version\":1}", true).errors()).anyMatch(error -> error
            .contains("缺少 fields 数组"));
        assertThat(validator.validate("  ", true).valid()).isFalse();
    }

    @Test
    void digestIsStableForSameSchema() {
        String schema = """
            {"fields":[{"id":"f1","key":"name","label":"姓名","type":"text"}]}
            """;
        String first = validator.validate(schema, true).digest();
        String second = validator.validate(schema, true).digest();
        assertThat(first).isEqualTo(second);
        assertThat(validator.validate(schema.replace("姓名", "名称"), true).digest()).isNotEqualTo(first);
    }

    @Test
    void acceptsVisibleReadonlyAndHiddenFields() {
        String schema = """
            {"fields":[
              {"id":"f1","key":"name","label":"姓名","type":"text","required":true},
              {"id":"f2","key":"dept","label":"部门","type":"text","readonly":true},
              {"id":"f3","key":"internal","label":"内部","type":"text","hidden":true}
            ]}
            """;
        OaFormValidationResult result = validator.validate(schema, true);
        assertThat(result.valid()).isTrue();
        assertThat(result.fieldCount()).isEqualTo(3);
    }

    @Test
    void rejectsRequiredFieldThatIsReadonlyOrHidden() {
        String schema = """
            {"fields":[
              {"id":"f1","key":"dept","label":"部门","type":"text","required":true,"readonly":true},
              {"id":"f2","key":"internal","label":"内部","type":"text","required":true,"hidden":true}
            ]}
            """;
        OaFormValidationResult result = validator.validate(schema, true);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).filteredOn(error -> error.contains("不能同时设为必填与只读/隐藏")).hasSize(2);
    }

    @Test
    void rejectsPublishWhenAllFieldsAreHidden() {
        String schema = """
            {"fields":[
              {"id":"f1","key":"internal","label":"内部","type":"text","hidden":true}
            ]}
            """;
        OaFormValidationResult draft = validator.validate(schema, false);
        assertThat(draft.valid()).isTrue();
        OaFormValidationResult publish = validator.validate(schema, true);
        assertThat(publish.valid()).isFalse();
        assertThat(publish.errors()).contains("表单至少需要一个可见字段才能发布");
    }
}
