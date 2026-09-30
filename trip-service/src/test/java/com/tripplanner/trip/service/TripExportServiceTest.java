package com.tripplanner.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.trip.dto.request.ExportTripRequest;
import com.tripplanner.trip.dto.response.ExportResponse;
import com.tripplanner.trip.entity.Trip;
import com.tripplanner.trip.entity.TripVersion;
import com.tripplanner.trip.repository.TripRepository;
import com.tripplanner.trip.repository.TripVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * TripExportService 单元测试
 * 覆盖导出修复：downloadUrl 指向文件端点、JSON UTF-8 中文、ICS 合法时间、PDF 头、权限与格式校验
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripExportServiceTest {

    @Mock
    private TripRepository tripRepository;

    @Mock
    private TripVersionRepository versionRepository;

    private TripExportService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new TripExportService(tripRepository, versionRepository, new JsonUtils(mapper));

        Trip trip = new Trip();
        trip.setId("t1");
        trip.setUserId("u1");
        trip.setTitle("成都三日游");
        trip.setRawInput("想看熊猫，顺便吃火锅");
        trip.setTransportMode("mixed");
        trip.setPreferences("{\"interests\":[\"博物馆\",\"美食\"]}");
        trip.setIsPublic(false);
        trip.setTimeStart(java.time.LocalDateTime.of(2026, 11, 10, 0, 0));
        trip.setTimeEnd(java.time.LocalDateTime.of(2026, 11, 12, 23, 59));
        when(tripRepository.selectById("t1")).thenReturn(trip);

        TripVersion version = new TripVersion();
        version.setId("v1");
        version.setTripId("t1");
        version.setVersionNum(2);
        version.setActivities("["
                + "{\"day\":1,\"poi_name\":\"成都大熊猫繁育研究基地\",\"poi_address\":\"成都市成华区\",\"activity_type\":\"visit\","
                + "\"scheduled_start\":\"2026-11-10T08:00:00\",\"scheduled_end\":\"2026-11-10T11:00:00\"},"
                + "{\"day\":1,\"poi_name\":\"午餐·龙抄手\",\"activity_type\":\"meal\","
                + "\"scheduled_start\":\"12:00\",\"scheduled_end\":\"13:00\"},"
                + "{\"day\":3,\"poi_name\":\"宽窄巷子\",\"activity_type\":\"visit\","
                + "\"scheduled_start\":\"09:30\",\"scheduled_end\":\"11:30\"}"
                + "]");
        version.setRoutes("[]");
        version.setStats("{\"每日平均景点数\":3}");
        when(versionRepository.findLatestByTripId("t1")).thenReturn(version);
        when(versionRepository.findByTripIdAndVersionNum("t1", 2)).thenReturn(version);
    }

    private ExportTripRequest request(String format) {
        ExportTripRequest request = new ExportTripRequest();
        request.setFormat(format);
        return request;
    }

    @Test
    @DisplayName("导出元数据 - JSON格式 - downloadUrl 指向文件下载端点")
    void exportTrip_json_shouldReturnFileEndpointUrl() {
        ExportResponse response = service.exportTrip("u1", "t1", request("json"));

        assertNotNull(response.getDownloadUrl());
        assertTrue(response.getDownloadUrl().startsWith("/api/trips/t1/export/file?format=json"));
        assertTrue(response.getDownloadUrl().contains("version=2"));
        assertTrue(response.getFilename().endsWith(".json"));
        assertTrue(response.getFileSize() > 0);
        assertEquals("application/json", response.getContentType());
    }

    @Test
    @DisplayName("导出文件 - JSON格式 - 中文标题与景点名以UTF-8保留")
    void renderFile_json_shouldKeepUtf8Chinese() {
        TripExportService.ExportFile file = service.renderFile("u1", "t1", request("json"));
        String json = new String(file.bytes(), StandardCharsets.UTF_8);

        assertEquals("application/json;charset=utf-8", file.contentType());
        assertTrue(file.filename().endsWith(".json"));
        assertTrue(json.contains("成都三日游"));
        assertTrue(json.contains("成都大熊猫繁育研究基地"));
        assertTrue(json.contains("午餐·龙抄手"));
    }

    @Test
    @DisplayName("导出文件 - ICS格式 - 完整ISO与纯时间(HH:mm)均生成合法DTSTART")
    void renderFile_ics_shouldContainValidDateTime() {
        TripExportService.ExportFile file = service.renderFile("u1", "t1", request("ics"));
        String ics = new String(file.bytes(), StandardCharsets.UTF_8);

        assertEquals("text/calendar;charset=utf-8", file.contentType());
        assertTrue(file.filename().endsWith(".ics"));
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"));
        assertTrue(ics.contains("BEGIN:VEVENT"));
        // 完整 ISO 时间
        assertTrue(ics.contains("DTSTART:20261110T080000"));
        assertTrue(ics.contains("DTEND:20261110T110000"));
        // 纯时间 HH:mm（day=1 → trip.timeStart 当天）
        assertTrue(ics.contains("DTSTART:20261110T120000"));
        assertTrue(ics.contains("DTEND:20261110T130000"));
        // 纯时间 + day=3 → trip.timeStart + 2 天
        assertTrue(ics.contains("DTSTART:20261112T093000"));
        assertTrue(ics.contains("SUMMARY:成都大熊猫繁育研究基地"));
        assertFalse(ics.contains("DTSTART:20261110T08000000"), "不得再出现旧版多拼00的非法时间");
        assertFalse(ics.contains("DTSTART:080000"), "纯时间不得缺日期");
    }

    @Test
    @DisplayName("导出文件 - PDF格式 - 返回有效PDF头与中文字体内容")
    void renderFile_pdf_shouldReturnPdfHeader() {
        TripExportService.ExportFile file = service.renderFile("u1", "t1", request("pdf"));

        assertEquals("application/pdf", file.contentType());
        assertTrue(file.filename().endsWith(".pdf"));
        String header = new String(file.bytes(), 0, 4, StandardCharsets.US_ASCII);
        assertEquals("%PDF", header);
        assertTrue(file.bytes().length > 1000, "PDF 应包含行程内容");
    }

    @Test
    @DisplayName("导出元数据 - PDF格式 - downloadUrl 指向文件下载端点")
    void exportTrip_pdf_shouldReturnFileEndpointUrl() {
        ExportResponse response = service.exportTrip("u1", "t1", request("pdf"));

        assertTrue(response.getDownloadUrl().startsWith("/api/trips/t1/export/file?format=pdf"));
        assertTrue(response.getFilename().endsWith(".pdf"));
        assertEquals("application/pdf", response.getContentType());
    }

    @Test
    @DisplayName("导出 - 非拥有者且非公开行程 - 应抛出无权限异常")
    void renderFile_notOwnerNotPublic_shouldThrowForbidden() {
        assertThrows(BizException.class, () -> service.renderFile("other-user", "t1", request("json")));
    }

    @Test
    @DisplayName("导出 - 行程不存在 - 应抛出未找到异常")
    void renderFile_tripNotFound_shouldThrowNotFound() {
        assertThrows(BizException.class, () -> service.renderFile("u1", "missing-trip", request("json")));
    }

    @Test
    @DisplayName("导出 - 不支持的格式（png） - 应抛出参数校验异常")
    void renderFile_unsupportedFormat_shouldThrowValidationError() {
        assertThrows(BizException.class, () -> service.renderFile("u1", "t1", request("png")));
    }

    @Test
    @DisplayName("导出 - 指定已存在版本 - 应使用请求的版本号")
    void renderFile_specifiedVersion_shouldUseRequestedVersion() {
        ExportTripRequest request = request("json");
        request.setVersion(2);

        ExportResponse response = service.exportTrip("u1", "t1", request);

        assertTrue(response.getDownloadUrl().contains("version=2"));
        assertEquals("application/json", response.getContentType());

        TripExportService.ExportFile file = service.renderFile("u1", "t1", request);
        assertTrue(new String(file.bytes(), StandardCharsets.UTF_8).contains("成都三日游"));
    }
}
