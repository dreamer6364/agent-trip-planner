package com.tripplanner.plan.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 餐厅候选打分测试（1.35.0）
 *
 * <p>距离优先：评分只折算成小分数（约 0~5 分），不再出现 1000 分量级的评分压过距离；
 * 无参考点回退模式下仍按评分降序。</p>
 */
class RestaurantNearbyScoreTest {

    @Test
    @DisplayName("nearbyScore - 有距离时 - 近店胜出，评分折算距离仅 0~5km")
    void score_distanceBeatsRating() {
        // 近店 0.2km/4.0 分 vs 远店 2.0km/4.9 分 → 近店胜出
        assertThat(RestaurantSearchService.nearbyScore(0.2, 4.0))
                .isLessThan(RestaurantSearchService.nearbyScore(2.0, 4.9));

        // 旧公式 d - rating*1000 会把 2.0km/4.9 分排在 0.2km/4.0 分之前
        assertThat(RestaurantSearchService.nearbyScore(2.0, 4.9)
                - RestaurantSearchService.nearbyScore(0.2, 4.0))
                .isLessThan(10.0);
    }

    @Test
    @DisplayName("nearbyScore - 无距离（-1）- 按评分降序")
    void score_noDistanceFallsBackToRating() {
        assertThat(RestaurantSearchService.nearbyScore(-1, 4.9))
                .isLessThan(RestaurantSearchService.nearbyScore(-1, 4.0));
    }

    @Test
    @DisplayName("nearbyScore - 评分缺失 - 视为 4.5 不产生空指针")
    void score_nullRatingTreatedAsDefault() {
        assertThat(RestaurantSearchService.nearbyScore(1.0, null))
                .isEqualTo(RestaurantSearchService.nearbyScore(1.0, 4.5));
    }
}
