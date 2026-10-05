package com.tripplanner.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.common.service.PermissionEvaluationService;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.trip.client.AuthUserClient;
import com.tripplanner.trip.client.PlanServiceClient;
import com.tripplanner.trip.dto.request.CreateTripRequest;
import com.tripplanner.trip.entity.Trip;
import com.tripplanner.trip.entity.TripVersion;
import com.tripplanner.trip.event.TripEventPublisher;
import com.tripplanner.trip.repository.ActivityRepository;
import com.tripplanner.trip.repository.TripRepository;
import com.tripplanner.trip.repository.TripVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 默认行程命名单元测试（1.36.0）
 * 规则：标题留空时按「规划城市 + 同城序号」自动命名（杭州、杭州2、杭州3），
 * 显式填写标题则原样保留；识别不出城市时兜底「未命名行程」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripServiceDefaultTitleTest {

    @Mock
    private TripRepository tripRepository;
    @Mock
    private TripVersionRepository versionRepository;
    @Mock
    private ActivityRepository activityRepository;
    @Mock
    private TripVersionService versionService;
    @Mock
    private TripEventPublisher eventPublisher;
    @Mock
    private PermissionEvaluationService permissionService;
    @Mock
    private InlinePlanningService inlinePlanner;
    @Mock
    private PlanServiceClient planServiceClient;
    @Mock
    private AuthUserClient authUserClient;

    private final List<Trip> existingTitles = new ArrayList<>();

    private TripService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new TripService(
                tripRepository, versionRepository, activityRepository,
                versionService, eventPublisher, permissionService,
                new JsonUtils(mapper), mapper,
                inlinePlanner, planServiceClient, authUserClient);

        TripVersion initial = new TripVersion();
        initial.setId("v1");
        initial.setStatus("draft");
        when(versionService.createInitialVersion(anyString(), any())).thenReturn(initial);
        when(versionRepository.findById("v1")).thenReturn(null);
        when(tripRepository.findTitlesByUserCity(anyString(), anyString()))
                .thenAnswer(inv -> new ArrayList<>(existingTitles));
    }

    private CreateTripRequest request(String title, String status) {
        CreateTripRequest request = new CreateTripRequest();
        request.setTitle(title);
        request.setRawInput("周末去杭州玩两天，看西湖");
        request.setTimeStart(start());
        request.setTimeEnd(LocalDateTime.of(2026, 10, 11, 18, 0));
        request.setTransportMode("transit");
        request.setPace("moderate");
        request.setStatus(status);
        return request;
    }

    private LocalDateTime start() {
        return LocalDateTime.of(2026, 10, 10, 9, 0);
    }

    private Trip savedTrip() {
        ArgumentCaptor<Trip> captor = ArgumentCaptor.forClass(Trip.class);
        org.mockito.Mockito.verify(tripRepository).insert(captor.capture());
        return captor.getValue();
    }

    private void mockPlanParse(String city) {
        when(planServiceClient.parseInput(anyString(), anyMap()))
                .thenReturn(ApiResponse.success(Map.of(
                        "places", List.of(),
                        "meals", List.of(),
                        "city", city)));
        when(planServiceClient.planItinerary(anyString(), anyMap()))
                .thenReturn(ApiResponse.success(Map.of("activities", List.of())));
    }

    @Test
    @DisplayName("创建行程 - 标题留空 - 默认名取规划城市")
    void createTrip_blankTitle_shouldUseCityName() {
        service.createTrip("u1", request("", "draft"));

        assertEquals("杭州", savedTrip().getTitle());
    }

    @Test
    @DisplayName("创建行程 - 标题为 null - 默认名取规划城市")
    void createTrip_nullTitle_shouldUseCityName() {
        service.createTrip("u1", request(null, "draft"));

        assertEquals("杭州", savedTrip().getTitle());
    }

    @Test
    @DisplayName("创建行程 - 同城已有同名行程 - 序号向后排（杭州2）")
    void createTrip_sameCityTaken_shouldAppendSequence() {
        existingTitles.add(trip("t1", "杭州"));

        service.createTrip("u1", request("  ", "draft"));

        assertEquals("杭州2", savedTrip().getTitle());
    }

    @Test
    @DisplayName("创建行程 - 同城同名加残留序号 - 取最大序号 +1（杭州4）")
    void createTrip_sameCityWithSequence_shouldUseMaxSequencePlusOne() {
        existingTitles.add(trip("t1", "杭州"));
        existingTitles.add(trip("t2", "杭州2"));
        existingTitles.add(trip("t3", "杭州3"));
        existingTitles.add(trip("t4", "杭州周末游"));

        service.createTrip("u1", request(null, "draft"));

        assertEquals("杭州4", savedTrip().getTitle());
    }

    @Test
    @DisplayName("创建行程 - 同名行程已被改名 - 序号位空缺回填城市名（杭州）")
    void createTrip_baseNameFree_shouldReuseCityName() {
        existingTitles.add(trip("t2", "杭州2"));

        service.createTrip("u1", request(null, "draft"));

        assertEquals("杭州", savedTrip().getTitle());
    }

    @Test
    @DisplayName("创建行程 - 显式填写标题 - 原样保留不覆盖")
    void createTrip_explicitTitle_shouldKeepIt() {
        service.createTrip("u1", request("我的杭州周末", "draft"));

        assertEquals("我的杭州周末", savedTrip().getTitle());
    }

    @Test
    @DisplayName("创建行程 - 完整规划且描述无城市 - 以 AI 解析出的城市命名")
    void createTrip_parsedCityAfterPlanning_shouldNameByParsedCity() {
        CreateTripRequest request = request(null, null);
        request.setRawInput("找个人少的地方放松两天");
        mockPlanParse("宁夏");

        service.createTrip("u1", request);

        assertEquals("宁夏", savedTrip().getTitle());
    }

    @Test
    @DisplayName("创建行程 - 完整规划且同城已有同名 - 解析后重算序号（宁夏2）")
    void createTrip_parsedCityWithExistingName_shouldAppendSequence() {
        existingTitles.add(trip("t1", "宁夏"));
        CreateTripRequest request = request(null, null);
        request.setRawInput("找个人少的地方放松两天");
        mockPlanParse("宁夏");

        service.createTrip("u1", request);

        assertEquals("宁夏2", savedTrip().getTitle());
    }

    @Test
    @DisplayName("创建行程 - 识别不出城市 - 兜底未命名行程")
    void createTrip_noCity_shouldFallbackToUntitled() {
        CreateTripRequest request = request(null, "draft");
        request.setRawInput("随便走走");

        service.createTrip("u1", request);

        assertEquals("未命名行程", savedTrip().getTitle());
    }

    private Trip trip(String id, String title) {
        Trip trip = new Trip();
        trip.setId(id);
        trip.setTitle(title);
        return trip;
    }
}
