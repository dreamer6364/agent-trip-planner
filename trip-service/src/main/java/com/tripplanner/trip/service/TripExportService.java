package com.tripplanner.trip.service;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Text;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行程导出服务
 *
 * 导出为 JSON / ICS / PDF / Template。元数据接口返回真实文件下载地址
 * （/api/trips/{id}/export/file），由前端带鉴权以 Blob 方式下载，
 * 避免 data: URL 被浏览器拦截与 window.open 弹窗限制。
 *
 * @author TripForge Team
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripExportService {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter CN_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter ICS_DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final Pattern TIME_HM = Pattern.compile("(\\d{1,2}:\\d{2})");

    /** PDF 中文字体候选（按优先级；系统字体不入库） */
    private static final String[] CJK_FONT_CANDIDATES = {
            "C:/Windows/Fonts/msyh.ttc,0",
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/simsun.ttc,0",
            "C:/Windows/Fonts/Deng.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc,0",
            "/System/Library/Fonts/PingFang.ttc,0"
    };

    private final TripRepository tripRepository;
    private final TripVersionRepository versionRepository;
    private final JsonUtils jsonUtils;

    /** 导出文件内容（下载接口返回） */
    public record ExportFile(byte[] bytes, String contentType, String filename) {}

    private record Resolved(Trip trip, TripVersion version, String format, ExportTripRequest request) {}

    /**
     * 导出元数据（下载地址、文件名、大小）
     */
    public ExportResponse exportTrip(String userId, String tripId, ExportTripRequest request) {
        Resolved r = resolve(userId, tripId, request);
        return switch (r.format()) {
            case "json" -> meta(r, "application/json", utf8Length(jsonContent(r.trip(), r.version())));
            case "ics" -> meta(r, "text/calendar", utf8Length(icsContent(r.trip(), r.version())));
            case "pdf" -> meta(r, "application/pdf", 0);
            case "template" -> meta(r, "application/json", utf8Length(templateJson(r.trip(), r.version())));
            default -> throw BizException.validationError("不支持的导出格式: " + r.format(), Map.of("format", r.format()));
        };
    }

    /**
     * 渲染导出文件内容（供 /export/file 下载）
     */
    public ExportFile renderFile(String userId, String tripId, ExportTripRequest request) {
        Resolved r = resolve(userId, tripId, request);
        String filename = buildFilename(r.trip(), r.version(), r.format());
        return switch (r.format()) {
            case "json" -> new ExportFile(
                    jsonContent(r.trip(), r.version()).getBytes(StandardCharsets.UTF_8),
                    "application/json;charset=utf-8", filename);
            case "ics" -> new ExportFile(
                    icsContent(r.trip(), r.version()).getBytes(StandardCharsets.UTF_8),
                    "text/calendar;charset=utf-8", filename);
            case "template" -> new ExportFile(
                    templateJson(r.trip(), r.version()).getBytes(StandardCharsets.UTF_8),
                    "application/json;charset=utf-8", filename);
            case "pdf" -> new ExportFile(
                    renderPdf(r.trip(), r.version(), r.request()),
                    "application/pdf", filename);
            default -> throw BizException.validationError("不支持的导出格式: " + r.format(), Map.of("format", r.format()));
        };
    }

    // ==================== 解析与元数据 ====================

    private Resolved resolve(String userId, String tripId, ExportTripRequest request) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程", tripId);
        }
        if (!trip.getUserId().equals(userId) && !Boolean.TRUE.equals(trip.getIsPublic())) {
            throw BizException.forbidden("无权导出该行程");
        }

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
        return new Resolved(trip, version, format, request);
    }

    private ExportResponse meta(Resolved r, String contentType, long fileSize) {
        return ExportResponse.builder()
                .downloadUrl(fileUrl(r))
                .filename(buildFilename(r.trip(), r.version(), r.format()))
                .fileSize(fileSize)
                .contentType(contentType)
                .build();
    }

    private String fileUrl(Resolved r) {
        ExportTripRequest req = r.request();
        return "/api/trips/" + r.trip().getId() + "/export/file"
                + "?format=" + r.format()
                + "&version=" + r.version().getVersionNum()
                + "&includeMap=" + Boolean.TRUE.equals(req.getIncludeMap())
                + "&includeStats=" + Boolean.TRUE.equals(req.getIncludeStats())
                + "&language=" + (req.getLanguage() != null ? req.getLanguage() : "zh-CN")
                + "&timezone=" + (req.getTimezone() != null ? req.getTimezone() : "Asia/Shanghai");
    }

    private String buildFilename(Trip trip, TripVersion version, String format) {
        String safe = sanitizeFilename(trip.getTitle());
        String date = LocalDateTime.now().format(FILE_DATE);
        return switch (format) {
            case "template" -> String.format("%s_template_%s.json", safe, date);
            case "ics" -> String.format("%s_v%d_%s.ics", safe, version.getVersionNum(), date);
            case "pdf" -> String.format("%s_v%d_%s.pdf", safe, version.getVersionNum(), date);
            default -> String.format("%s_v%d_%s.json", safe, version.getVersionNum(), date);
        };
    }

    private int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ==================== JSON / Template ====================

    private String jsonContent(Trip trip, TripVersion version) {
        Map<String, Object> tripMap = new LinkedHashMap<>();
        tripMap.put("id", trip.getId());
        tripMap.put("title", trip.getTitle());
        tripMap.put("rawInput", trip.getRawInput());
        tripMap.put("timeStart", trip.getTimeStart());
        tripMap.put("timeEnd", trip.getTimeEnd());
        tripMap.put("transportMode", trip.getTransportMode());
        tripMap.put("preferences", mapOrEmpty(jsonUtils.fromJson(trip.getPreferences(), Map.class)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trip", tripMap);
        data.put("version", versionMap(version));
        data.put("exportedAt", LocalDateTime.now());
        return jsonUtils.toJson(data);
    }

    private String templateJson(Trip trip, TripVersion version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", trip.getTitle());
        data.put("rawInput", trip.getRawInput());
        data.put("timeStart", trip.getTimeStart());
        data.put("timeEnd", trip.getTimeEnd());
        data.put("transportMode", trip.getTransportMode());
        data.put("preferences", mapOrEmpty(jsonUtils.fromJson(trip.getPreferences(), Map.class)));
        Map<String, Object> versionMap = new LinkedHashMap<>();
        versionMap.put("activities", listOrEmpty(jsonUtils.fromJson(version.getActivities(), List.class)));
        versionMap.put("routes", listOrEmpty(jsonUtils.fromJson(version.getRoutes(), List.class)));
        data.put("version", versionMap);
        data.put("isTemplate", true);
        return jsonUtils.toJson(data);
    }

    private Map<String, Object> versionMap(TripVersion version) {
        Map<String, Object> versionMap = new LinkedHashMap<>();
        versionMap.put("versionNum", version.getVersionNum());
        versionMap.put("activities", listOrEmpty(jsonUtils.fromJson(version.getActivities(), List.class)));
        versionMap.put("routes", listOrEmpty(jsonUtils.fromJson(version.getRoutes(), List.class)));
        versionMap.put("conflicts", listOrEmpty(jsonUtils.fromJson(version.getConflicts(), List.class)));
        versionMap.put("stats", mapOrEmpty(jsonUtils.fromJson(version.getStats(), Map.class)));
        return versionMap;
    }

    // ==================== ICS ====================

    private String icsContent(Trip trip, TripVersion version) {
        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\n");
        ics.append("VERSION:2.0\r\n");
        ics.append("PRODID:-//TripForge//Trip Itinerary//ZH\r\n");
        ics.append("CALSCALE:GREGORIAN\r\n");
        ics.append("METHOD:PUBLISH\r\n");
        ics.append("X-WR-CALNAME:").append(escapeIcsText(trip.getTitle())).append("\r\n");
        ics.append("X-WR-TIMEZONE:Asia/Shanghai\r\n");

        LocalDate baseDate = trip.getTimeStart() != null ? trip.getTimeStart().toLocalDate() : null;

        for (Object actObj : listOrEmpty(jsonUtils.fromJson(version.getActivities(), List.class))) {
            if (!(actObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> act = (Map<String, Object>) actObj;

            LocalDate dayDate = dayDate(act, baseDate);
            LocalDateTime startLdt = parseActivityTime(
                    field(act, "scheduled_start", "scheduledStart", "startTime", "start_time"), dayDate);
            if (startLdt == null) {
                continue;
            }
            LocalDateTime endLdt = parseActivityTime(
                    field(act, "scheduled_end", "scheduledEnd", "endTime", "end_time"), dayDate);
            if (endLdt == null || !endLdt.isAfter(startLdt)) {
                endLdt = startLdt.plusHours(2);
            }

            String summary = field(act, "poi_name", "name", "poiName", "title");
            String description = field(act, "notes", "description");
            String location = field(act, "poi_address", "poiAddress", "address");

            ics.append("BEGIN:VEVENT\r\n");
            ics.append("UID:").append(UUID.randomUUID()).append("@tripforge\r\n");
            ics.append("DTSTAMP:").append(LocalDateTime.now().format(ICS_DT)).append("\r\n");
            ics.append("DTSTART:").append(startLdt.format(ICS_DT)).append("\r\n");
            ics.append("DTEND:").append(endLdt.format(ICS_DT)).append("\r\n");
            ics.append("SUMMARY:").append(escapeIcsText(summary != null ? summary : "行程")).append("\r\n");
            if (description != null) {
                ics.append("DESCRIPTION:").append(escapeIcsText(description)).append("\r\n");
            }
            if (location != null) {
                ics.append("LOCATION:").append(escapeIcsText(location)).append("\r\n");
            }
            ics.append("END:VEVENT\r\n");
        }

        ics.append("END:VCALENDAR\r\n");
        return ics.toString();
    }

    /**
     * 活动所在日期：trip.timeStart 日期 + (day-1) 天偏移；无 day 则为第 1 天
     */
    private LocalDate dayDate(Map<String, Object> act, LocalDate baseDate) {
        if (baseDate == null) {
            return null;
        }
        Object dayVal = act.get("day");
        int day = dayVal instanceof Number ? ((Number) dayVal).intValue() : 1;
        return baseDate.plusDays(Math.max(0, day - 1));
    }

    /**
     * 活动时间 → LocalDateTime。支持完整 ISO 时间与 "HH:mm" 纯时间（与 dayDate 合成日期），
     * 无法解析时返回 null（跳过该事件）。旧版直接多拼 "00" 导致日历导入失败。
     */
    private LocalDateTime parseActivityTime(String value, LocalDate dayDate) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String str = value.trim();
        try {
            return LocalDateTime.parse(str, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception ignore) {
            // 继续尝试其他格式
        }
        Matcher timeOnly = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::\\d{2})?$").matcher(str);
        if (timeOnly.matches()) {
            if (dayDate == null) {
                return null;
            }
            return dayDate.atTime(Integer.parseInt(timeOnly.group(1)), Integer.parseInt(timeOnly.group(2)));
        }
        String digits = str.replaceAll("[^0-9]", "");
        if (digits.length() >= 14) {
            try {
                return LocalDateTime.parse(
                        digits.substring(0, 8) + "T" + digits.substring(8, 14),
                        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String escapeIcsText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace(";", "\\;")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    // ==================== PDF ====================

    /**
     * 生成行程 PDF（iText7 + 系统中文字体，内容自动分页）
     */
    private byte[] renderPdf(Trip trip, TripVersion version, ExportTripRequest request) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(baos));
            try (Document doc = new Document(pdfDoc, PageSize.A4)) {
                doc.setMargins(42, 44, 42, 44);
                doc.setFont(loadCjkFont(pdfDoc));

                doc.add(new Paragraph(nvl(trip.getTitle())).setFontSize(20));
                String meta = fmtDate(trip.getTimeStart()) + " 至 " + fmtDate(trip.getTimeEnd())
                        + (trip.getTransportMode() != null && !trip.getTransportMode().isBlank()
                            ? "  ·  交通方式: " + trip.getTransportMode() : "");
                doc.add(new Paragraph(meta).setFontSize(10)
                        .setFontColor(ColorConstants.GRAY).setMarginTop(4));
                doc.add(new Paragraph("版本 v" + version.getVersionNum()
                                + "  ·  导出时间: " + LocalDateTime.now().format(CN_DATETIME))
                        .setFontSize(9).setFontColor(ColorConstants.GRAY).setMarginBottom(8));

                for (Map.Entry<Integer, List<Map<String, Object>>> entry : groupByDay(version).entrySet()) {
                    doc.add(new Paragraph("第 " + entry.getKey() + " 天")
                            .setFontSize(14).setMarginTop(12).setMarginBottom(4)
                            .setFontColor(ColorConstants.DARK_GRAY));
                    for (Map<String, Object> act : entry.getValue()) {
                        String time = timeRange(act);
                        String name = field(act, "poi_name", "name", "poiName", "title");
                        String type = typeLabel(field(act, "activity_type", "type"));

                        Paragraph head = new Paragraph();
                        if (!time.isEmpty()) {
                            head.add(new Text(time).setFontSize(11));
                        }
                        head.add(new Text((time.isEmpty() ? "" : "   ") + nvl(name)).setFontSize(11));
                        if (!type.isEmpty()) {
                            head.add(new Text("   [" + type + "]").setFontSize(9)
                                    .setFontColor(ColorConstants.GRAY));
                        }
                        head.setMarginTop(4).setMarginBottom(0);
                        doc.add(head);

                        String address = field(act, "poi_address", "poiAddress", "address");
                        if (address != null) {
                            doc.add(new Paragraph(address).setFontSize(9)
                                    .setFontColor(ColorConstants.GRAY)
                                    .setMarginTop(1).setMarginLeft(12));
                        }
                    }
                }

                if (Boolean.TRUE.equals(request.getIncludeStats())) {
                    addStatsSection(doc, version);
                }
            }
            return baos.toByteArray();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成 PDF 失败: {}", e.getMessage(), e);
            throw BizException.internalError("生成 PDF 失败: " + e.getMessage());
        }
    }

    private void addStatsSection(Document doc, TripVersion version) {
        Map<String, Object> stats = mapOrEmpty(jsonUtils.fromJson(version.getStats(), Map.class));
        if (stats.isEmpty()) {
            return;
        }
        doc.add(new Paragraph("统计").setFontSize(14)
                .setMarginTop(14).setMarginBottom(4)
                .setFontColor(ColorConstants.DARK_GRAY));
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            String value = (e.getValue() instanceof Map || e.getValue() instanceof List)
                    ? jsonUtils.toJson(e.getValue())
                    : String.valueOf(e.getValue());
            doc.add(new Paragraph(nvl(e.getKey()) + ": " + value).setFontSize(10).setMarginTop(1));
        }
    }

    /**
     * 加载系统中文字体（优先微软雅黑），全部候选失败时报业务错误而非输出乱码 PDF
     */
    private PdfFont loadCjkFont(PdfDocument pdfDoc) throws Exception {
        for (String candidate : CJK_FONT_CANDIDATES) {
            String path = candidate.contains(",") ? candidate.substring(0, candidate.indexOf(',')) : candidate;
            if (!new File(path).exists()) {
                continue;
            }
            try {
                return PdfFontFactory.createFont(
                        candidate, PdfEncodings.IDENTITY_H,
                        PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
            } catch (Exception e) {
                log.warn("加载 PDF 字体失败 {}: {}", candidate, e.getMessage());
            }
        }
        throw BizException.internalError("服务器未找到可用的中文字体，无法生成 PDF");
    }

    private Map<Integer, List<Map<String, Object>>> groupByDay(TripVersion version) {
        Map<Integer, List<Map<String, Object>>> byDay = new TreeMap<>();
        for (Object actObj : listOrEmpty(jsonUtils.fromJson(version.getActivities(), List.class))) {
            if (!(actObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> act = (Map<String, Object>) actObj;
            Object dayVal = act.get("day");
            int day = dayVal instanceof Number ? ((Number) dayVal).intValue() : 1;
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(act);
        }
        for (List<Map<String, Object>> list : byDay.values()) {
            list.sort(Comparator.comparingInt(a -> toMinutes(field(a, "scheduled_start", "scheduledStart", "startTime"))));
        }
        return byDay;
    }

    private String timeRange(Map<String, Object> act) {
        String start = fmtHm(field(act, "scheduled_start", "scheduledStart", "startTime"));
        String end = fmtHm(field(act, "scheduled_end", "scheduledEnd", "endTime"));
        if (start.isEmpty() && end.isEmpty()) {
            return "";
        }
        return start + "~" + end;
    }

    private String fmtHm(String value) {
        if (value == null) {
            return "";
        }
        Matcher m = TIME_HM.matcher(value);
        return m.find() ? m.group(1) : "";
    }

    private int toMinutes(String value) {
        String hm = fmtHm(value);
        if (hm.isEmpty()) {
            return 0;
        }
        String[] parts = hm.split(":");
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String typeLabel(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        return switch (type) {
            case "visit" -> "游览";
            case "meal" -> "餐饮";
            case "transit" -> "交通";
            case "rest" -> "休息";
            case "shopping" -> "购物";
            default -> type;
        };
    }

    // ==================== 通用工具 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOrEmpty(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> listOrEmpty(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    /**
     * 按优先级取活动字段（兼容 snake_case / camelCase 两套键名）
     */
    private String field(Map<String, Object> act, String... keys) {
        for (String key : keys) {
            Object v = act.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private String fmtDate(LocalDateTime time) {
        return time == null ? "-" : time.format(CN_DATETIME);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "trip";
        }
        String cleaned = filename.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) {
            return "trip";
        }
        return cleaned.length() > 50 ? cleaned.substring(0, 50) : cleaned;
    }
}
