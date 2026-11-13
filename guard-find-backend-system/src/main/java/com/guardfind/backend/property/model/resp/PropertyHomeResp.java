package com.guardfind.backend.property.model.resp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class PropertyHomeResp {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
        .ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    @Size(max = 20)
    @Pattern(regexp = "^\\+?[0-9][0-9-]{4,18}[0-9]$", message = "客服电话格式不合法")
    private String supportPhone;
    @Size(max = 100)
    private String serviceHours;
    @NotNull
    @Size(max = 5)
    private List<@NotNull @Valid Banner> banners = new ArrayList<>();
    @NotNull
    @Size(max = 20)
    private List<@NotNull @Valid Activity> activities = new ArrayList<>();

    @AssertTrue(message = "Banner 或活动 ID 不能重复")
    @JsonIgnore
    public boolean isIdsUnique() {
        return (banners == null || unique(banners.stream().filter(java.util.Objects::nonNull).map(Banner::getId).toList()))
            && (activities == null || unique(activities.stream().filter(java.util.Objects::nonNull).map(Activity::getId).toList()));
    }

    private static boolean unique(List<?> ids) {
        return new HashSet<>(ids).size() == ids.size();
    }

    private static boolean validUrl(String value) {
        if (value == null) {
            return true;
        }
        try {
            var uri = URI.create(value);
            return value.matches("^https?://[^\\s]+$") && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static LocalDateTime parseTime(String value) {
        return value == null ? null : LocalDateTime.parse(value, DATE_TIME);
    }

    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Banner {
        @NotBlank
        @Pattern(regexp = "^[a-z0-9-]{1,40}$")
        private String id;
        @NotBlank
        @Size(max = 100)
        private String title;
        @Size(max = 200)
        private String subtitle;
        @Size(max = 1000)
        private String imageUrl;
        @NotBlank
        @Pattern(regexp = "^(all|recommended|activity|property)$")
        private String targetType;
        @Size(max = 40)
        private String targetId;
        private boolean enabled;

        @AssertTrue(message = "图片必须为无空白的 HTTP(S) 地址")
        @JsonIgnore
        public boolean isImageUrlValid() {
            return validUrl(imageUrl);
        }

        @AssertTrue(message = "Banner 跳转目标格式不合法")
        @JsonIgnore
        public boolean isTargetValid() {
            if ("all".equals(targetType) || "recommended".equals(targetType)) {
                return targetId == null;
            }
            if ("activity".equals(targetType)) {
                return targetId != null && targetId.matches("^[a-z0-9-]{1,40}$");
            }
            if ("property".equals(targetType)) {
                try {
                    return targetId != null && targetId.matches("^[1-9][0-9]{0,18}$") && Long.parseLong(targetId) > 0;
                } catch (NumberFormatException ex) {
                    return false;
                }
            }
            return true;
        }
    }

    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Activity {
        @NotBlank
        @Pattern(regexp = "^[a-z0-9-]{1,40}$")
        private String id;
        @NotBlank
        @Size(max = 100)
        private String title;
        @Size(max = 2000)
        private String description;
        @Size(max = 1000)
        private String imageUrl;
        @Size(max = 19)
        private String startsAt;
        @Size(max = 19)
        private String endsAt;
        private boolean enabled;
        @NotNull
        @Size(max = 100)
        private List<@NotNull @Positive Long> propertyIds = new ArrayList<>();

        @AssertTrue(message = "图片必须为无空白的 HTTP(S) 地址")
        @JsonIgnore
        public boolean isImageUrlValid() {
            return validUrl(imageUrl);
        }

        @AssertTrue(message = "活动日期须为 yyyy-MM-dd HH:mm:ss 且结束时间晚于开始时间")
        @JsonIgnore
        public boolean isTimeRangeValid() {
            try {
                if (startsAt != null && !startsAt.matches("^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$")
                    || endsAt != null && !endsAt.matches("^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$")) {
                    return false;
                }
                var start = parseTime(startsAt);
                var end = parseTime(endsAt);
                return start == null || end == null || start.isBefore(end);
            } catch (java.time.format.DateTimeParseException ex) {
                return false;
            }
        }

        public boolean activeAt(LocalDateTime now) {
            return enabled && isTimeRangeValid() && (startsAt == null || !parseTime(startsAt).isAfter(now))
                && (endsAt == null || parseTime(endsAt).isAfter(now));
        }
    }
}
