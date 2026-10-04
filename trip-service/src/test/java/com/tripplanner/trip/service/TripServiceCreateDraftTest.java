package com.tripplanner.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.trip.client.AuthUserClient;
import com.tripplanner.trip.client.PlanServiceClient;
import com.tripplanner.trip.dto.request.CreateTripRequest;
import com.tripplanner.trip.dto.response.TripResponse;
import com.tripplanner.trip.entity.Trip;
import com.tripplanner.trip.entity.TripVersion;
import com.tripplanner.trip.event.TripEventPublisher;
import com.tripplanner.trip.repository.ActivityRepository;
import com.tripplanner.trip.repository.TripRepository;
import com.tripplanner.trip.repository.TripVersionRepository;
import com.tripplanner.common.service.PermissionEvaluationService;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 创建行程草稿模式单元测试（1.34.0）
 * 覆盖：status=draft 仅保存不规划；缺省 status 走完整 AI 规划路径（回归）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripServiceCreateDraftTest {

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
    }

    private CreateTripRequest baseRequest(String status) {
        CreateTripRequest request = new CreateTripRequest();
        request.setTitle("杭州周末");
        request.setRawInput("周末去杭州玩两天，看西湖");
        request.setTimeStart(LocalDateTime.of(2026, 10, 10, 9, 0));
        request.setTimeEnd(LocalDateTime.of(2026, 10, 11, 18, 0));
        request.setTransportMode("transit");
        request.setPace("moderate");
        request.setStatus(status);
        return request;
    }

    @Test
    @DisplayName("创建行程 - status=draft - 保存为草稿且完全跳过 AI 规划")
    void createTrip_draftStatus_savesWithoutPlanning() {
        TripResponse resp = service.createTrip("u1", baseRequest("draft"));

        ArgumentCaptor<Trip> captor = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository).insert(captor.capture());
        Trip saved = captor.getValue();
        assertEquals("draft", saved.getStatus());
        assertEquals("draft", resp.getStatus());
        assertEquals(saved.getId(), resp.getId());

        verify(versionService).createInitialVersion(anyString(), any());
        verifyNoInteractions(planServiceClient, inlinePlanner, eventPublisher);
    }

    @Test
    @DisplayName("创建行程 - 缺省 status - 初始 planning 且执行完整 AI 规划至 completed")
    void createTrip_withoutStatus_runsFullPlanning() {
        // Trip 为同一引用，insert 时刻快照初始状态（后续会被规划流程改写）
        java.util.concurrent.atomic.AtomicReference<String> statusAtInsert =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            statusAtInsert.set(((Trip) inv.getArgument(0)).getStatus());
            return null;
        }).when(tripRepository).insert(any(Trip.class));

        when(planServiceClient.parseInput(anyString(), anyMap()))
                .thenReturn(ApiResponse.success(Map.of(
                        "places", List.of(),
                        "meals", List.of(),
                        "city", "杭州")));
        when(planServiceClient.planItinerary(anyString(), anyMap()))
                .thenReturn(ApiResponse.success(Map.of("activities", List.of())));

        TripResponse resp = service.createTrip("u1", baseRequest(null));

        assertEquals("planning", statusAtInsert.get());
        assertEquals("completed", resp.getStatus());
        verify(planServiceClient).parseInput(anyString(), anyMap());
        verify(planServiceClient).planItinerary(anyString(), anyMap());
    }

    @Test
    @DisplayName("创建行程 - status=草稿大小写不敏感 - 仍然只保存不规划")
    void createTrip_draftStatusCaseInsensitive_savesWithoutPlanning() {
        TripResponse resp = service.createTrip("u1", baseRequest("DRAFT"));

        assertEquals("draft", resp.getStatus());
        verifyNoInteractions(planServiceClient, inlinePlanner);
    }
}
