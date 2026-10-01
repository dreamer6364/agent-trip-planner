package com.tripplanner.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.trip.client.AuthUserClient;
import com.tripplanner.trip.dto.response.TripListResponse;
import com.tripplanner.trip.entity.Trip;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 公开行程作者显示名单元测试
 * 覆盖：正常填充用户名、auth 服务不可用 fail-open 降级、昵称缺失回退
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripServiceAuthorNameTest {

    @Mock
    private TripRepository tripRepository;

    @Mock
    private TripVersionRepository versionRepository;

    @Mock
    private AuthUserClient authUserClient;

    private TripService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new TripService(
                tripRepository, versionRepository,
                null, null, null, null,
                new JsonUtils(mapper), mapper,
                null, null,
                authUserClient);
    }

    private Trip publicTrip(String id, String userId) {
        Trip trip = new Trip();
        trip.setId(id);
        trip.setUserId(userId);
        trip.setTitle("成都三日游");
        trip.setRawInput("想看熊猫");
        trip.setParsedInput("{}");
        trip.setPreferences("{}");
        trip.setIsPublic(true);
        trip.setViewCount(0);
        return trip;
    }

    @Test
    @DisplayName("公开行程列表 - 正常场景 - 作者显示名填充为用户名")
    void listPublicTrips_normalCase_shouldFillAuthorName() {
        when(tripRepository.findPublicTrips(0, 10)).thenReturn(List.of(publicTrip("t1", "u1")));
        when(tripRepository.countPublicTrips()).thenReturn(1L);
        when(authUserClient.getUserNames(anyList()))
                .thenReturn(ApiResponse.success(Map.of("u1", "张三")));

        TripListResponse resp = service.listPublicTrips(1, 10, null);

        assertEquals(1, resp.getItems().size());
        assertEquals("张三", resp.getItems().get(0).getAuthorName());
        assertEquals("u1", resp.getItems().get(0).getUserId());
    }

    @Test
    @DisplayName("公开行程列表 - 用户名服务不可用 - 降级为无作者名且列表不受影响")
    void listPublicTrips_authDown_shouldFailOpen() {
        when(tripRepository.findPublicTrips(0, 10)).thenReturn(List.of(publicTrip("t1", "u1")));
        when(tripRepository.countPublicTrips()).thenReturn(1L);
        when(authUserClient.getUserNames(anyList())).thenThrow(new RuntimeException("connection refused"));

        TripListResponse resp = service.listPublicTrips(1, 10, null);

        assertEquals(1, resp.getItems().size());
        assertNull(resp.getItems().get(0).getAuthorName());
        assertEquals("成都三日游", resp.getItems().get(0).getTitle());
    }

    @Test
    @DisplayName("公开行程列表 - 用户名缺失 - 作者名为空供前端回退到 userId")
    void listPublicTrips_missingName_shouldReturnNullAuthorName() {
        when(tripRepository.findPublicTrips(0, 10)).thenReturn(List.of(publicTrip("t1", "u1")));
        when(tripRepository.countPublicTrips()).thenReturn(1L);
        when(authUserClient.getUserNames(anyList())).thenReturn(ApiResponse.success(Map.of()));

        TripListResponse resp = service.listPublicTrips(1, 10, null);

        assertEquals(1, resp.getItems().size());
        assertNull(resp.getItems().get(0).getAuthorName());
    }

    @Test
    @DisplayName("公开行程列表 - 关键词搜索 - 作者名同样被补充")
    void listPublicTrips_withKeyword_shouldEnrichAuthorName() {
        when(tripRepository.searchPublicTrips("成都", 0, 10))
                .thenReturn(List.of(publicTrip("t1", "u1"), publicTrip("t2", "u2")));
        when(tripRepository.countPublicTripsByKeyword("成都")).thenReturn(2L);
        when(authUserClient.getUserNames(anyList()))
                .thenReturn(ApiResponse.success(Map.of("u1", "张三", "u2", "Li Lei")));

        TripListResponse resp = service.listPublicTrips(1, 10, "成都");

        assertEquals(2, resp.getItems().size());
        assertTrue(resp.getItems().stream()
                .allMatch(i -> i.getAuthorName() != null && !i.getAuthorName().isBlank()));
        assertEquals("张三", resp.getItems().get(0).getAuthorName());
        assertEquals("Li Lei", resp.getItems().get(1).getAuthorName());
    }
}
