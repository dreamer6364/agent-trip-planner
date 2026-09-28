package com.tripplanner.trip.service;

import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.trip.dto.request.ExportTripRequest;
import com.tripplanner.trip.dto.response.ExportResponse;
import com.tripplanner.trip.entity.Trip;
import com.tripplanner.trip.entity.TripVersion;
import com.tripplanner.trip.repository.TripRepository;
import com.tripplanner.trip.repository.TripVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 行程导出服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripExportService {

    private final TripRepository tripRepository;
    private final TripVersionRepository versionRepository;
    private final JsonUtils jsonUtils;

    /**
     * 导出行程
     */
    public ExportResponse exportTrip(String userId, String tripId, ExportTripRequest request) throws IOException {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程", tripId);
        }

        // 权限检查
        if (!trip.getUserId().equals(userId) && !Boolean.TRUE.equals(trip.getIsPublic())) {
            throw BizException.forbidden("无权导出该行程");
        }

        // 获取版本
        TripVersion version;
        if (request.getVersion() != null) {
            version = versionRepository.findByTripIdAndVersionNum(tripId, request.getVersion());
        } else {
            version = versionRepository.findLatestByTripId(tripId);
        }

        if (version == null) {
            throw BizException.notFound("版本", request.getVersion() != null ? request.getVersion().toString() : "latest");
        }

        String format = request.getFormat() != null ? request.getFormat().toLowerCase() : "json";

        return switch (format) {
            case "json" -> exportJson(trip, version);
            case "ics" -> exportIcs(trip, version);
            case "pdf" -> exportPdf(trip, version, request);
            case "png" -> exportPng(trip, version, request);
            case "template" -> exportTemplate(trip, version);
            default -> throw BizException.validationError("不支持的导出格式: " + format, Map.of("format", format));
        };
    }

    private ExportResponse exportJson(Trip trip, TripVersion version) {
        Map<String, Object> data = Map.of(
                "trip", Map.of(
                        "id", trip.getId(),
                        "title", trip.getTitle(),
                        "rawInput", trip.getRawInput(),
                        "timeStart", trip.getTimeStart(),
                        "timeEnd", trip.getTimeEnd(),
                        "transportMode", trip.getTransportMode(),
                        "preferences", jsonUtils.fromJson(trip.getPreferences(), Map.class)
                ),
                "version", Map.of(
                        "versionNum", version.getVersionNum(),
                        "activities", jsonUtils.fromJson(version.getActivities(), List.class),
                        "routes", jsonUtils.fromJson(version.getRoutes(), List.class),
                        "conflicts", jsonUtils.fromJson(version.getConflicts(), List.class),
                        "stats", jsonUtils.fromJson(version.getStats(), Map.class)
                ),
                "exportedAt", LocalDateTime.now()
        );

        String json = jsonUtils.toJson(data);
        String filename = String.format("%s_v%d_%s.json", 
                sanitizeFilename(trip.getTitle()), 
                version.getVersionNum(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));

        // 实际项目中：上传到对象存储，返回预签名 URL
        // 这里简化：返回 Base64 编码内容
        String base64 = Base64.getEncoder().encodeToString(json.getBytes());
        String downloadUrl = "data:application/json;base64," + base64;

        return ExportResponse.builder()
                .downloadUrl(downloadUrl)
                .filename(filename)
                .fileSize(json.getBytes().length)
                .contentType("application/json")
                .build();
    }

    private ExportResponse exportIcs(Trip trip, TripVersion version) {
        // TODO: 使用 ical4j 生成 ICS 格式
        String icsContent = generateIcsContent(trip, version);
        String filename = String.format("%s_v%d_%s.ics", 
                sanitizeFilename(trip.getTitle()), 
                version.getVersionNum(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));

        String base64 = Base64.getEncoder().encodeToString(icsContent.getBytes());
        String downloadUrl = "data:text/calendar;base64," + base64;

        return ExportResponse.builder()
                .downloadUrl(downloadUrl)
                .filename(filename)
                .fileSize(icsContent.getBytes().length)
                .contentType("text/calendar")
                .build();
    }

    private ExportResponse exportPdf(Trip trip, TripVersion version, ExportTripRequest request) {
        // TODO: 使用 iText7 + Thymeleaf 模板生成 PDF
        // 这里简化返回占位符
        String filename = String.format("%s_v%d_%s.pdf", 
                sanitizeFilename(trip.getTitle()), 
                version.getVersionNum(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));

        return ExportResponse.builder()
                .downloadUrl("/api/trips/" + trip.getId() + "/export/pdf?version=" + version.getVersionNum())
                .filename(filename)
                .fileSize(0)
                .contentType("application/pdf")
                .build();
    }

    private ExportResponse exportPng(Trip trip, TripVersion version, ExportTripRequest request) {
        // TODO: 使用无头浏览器或地图库生成 PNG
        String filename = String.format("%s_v%d_%s.png", 
                sanitizeFilename(trip.getTitle()), 
                version.getVersionNum(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));

        return ExportResponse.builder()
                .downloadUrl("/api/trips/" + trip.getId() + "/export/png?version=" + version.getVersionNum())
                .filename(filename)
                .fileSize(0)
                .contentType("image/png")
                .build();
    }

    private ExportResponse exportTemplate(Trip trip, TripVersion version) {
        // 去除个人信息，生成模板
        Map<String, Object> template = Map.of(
                "title", trip.getTitle(),
                "rawInput", trip.getRawInput(),
                "timeStart", trip.getTimeStart(),
                "timeEnd", trip.getTimeEnd(),
                "transportMode", trip.getTransportMode(),
                "preferences", jsonUtils.fromJson(trip.getPreferences(), Map.class),
                "version", Map.of(
                        "activities", jsonUtils.fromJson(version.getActivities(), List.class),
                        "routes", jsonUtils.fromJson(version.getRoutes(), List.class)
                ),
                "isTemplate", true
        );

        String json = jsonUtils.toJson(template);
        String filename = String.format("%s_template_%s.json", 
                sanitizeFilename(trip.getTitle()),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));

        String base64 = Base64.getEncoder().encodeToString(json.getBytes());
        String downloadUrl = "data:application/json;base64," + base64;

        return ExportResponse.builder()
                .downloadUrl(downloadUrl)
                .filename(filename)
                .fileSize(json.getBytes().length)
                .contentType("application/json")
                .build();
    }

    private String generateIcsContent(Trip trip, TripVersion version) {
        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\n");
        ics.append("VERSION:2.0\r\n");
        ics.append("PRODID:-//Trip Planner//Trip Itinerary//EN\r\n");
        ics.append("CALSCALE:GREGORIAN\r\n");
        ics.append("METHOD:PUBLISH\r\n");
        ics.append("X-WR-CALNAME:").append(trip.getTitle()).append("\r\n");

        var activities = jsonUtils.fromJson(version.getActivities(), List.class);
        if (activities != null) {
            for (Object actObj : activities) {
                @SuppressWarnings("unchecked")
                Map<String, Object> act = (Map<String, Object>) actObj;
                
                String uid = UUID.randomUUID() + "@tripplanner";
                String dtStart = formatIcsDateTime(act.get("scheduledStart"));
                String dtEnd = formatIcsDateTime(act.get("scheduledEnd"));
                String summary = (String) act.get("poiName");
                String description = (String) act.get("notes");
                String location = (String) act.get("poiAddress");

                ics.append("BEGIN:VEVENT\r\n");
                ics.append("UID:").append(uid).append("\r\n");
                ics.append("DTSTAMP:").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))).append("\r\n");
                ics.append("DTSTART:").append(dtStart).append("\r\n");
                ics.append("DTEND:").append(dtEnd).append("\r\n");
                ics.append("SUMMARY:").append(escapeIcsText(summary)).append("\r\n");
                if (description != null) {
                    ics.append("DESCRIPTION:").append(escapeIcsText(description)).append("\r\n");
                }
                if (location != null) {
                    ics.append("LOCATION:").append(escapeIcsText(location)).append("\r\n");
                }
                ics.append("END:VEVENT\r\n");
            }
        }

        ics.append("END:VCALENDAR\r\n");
        return ics.toString();
    }

    private String formatIcsDateTime(Object dateTime) {
        if (dateTime == null) return "";
        String str = dateTime.toString();
        // 简化处理：假设格式为 yyyy-MM-ddTHH:mm:ss
        return str.replace("-", "").replace(":", "").replace("T", "T") + "00";
    }

    private String escapeIcsText(String text) {
        if (text == null) return "";
        return text.replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n");
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "trip";
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_").substring(0, Math.min(50, filename.length()));
    }
}