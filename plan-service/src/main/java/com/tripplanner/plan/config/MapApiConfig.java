package com.tripplanner.plan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 地图 API 配置 (高德/百度)
 */
@Configuration
public class MapApiConfig {

    @Value("${amap.api-key:}")
    private String amapApiKey;

    @Value("${amap.api-secret:}")
    private String amapApiSecret;

    @Value("${amap.geocode-url:https://restapi.amap.com/v3/geocode/geo}")
    private String amapGeocodeUrl;

    @Value("${amap.regeocode-url:https://restapi.amap.com/v3/geocode/regeo}")
    private String amapRegeocodeUrl;

    @Value("${amap.route-url:https://restapi.amap.com/v3/direction/driving}")
    private String amapRouteUrl;

    @Value("${amap.transit-url:https://restapi.amap.com/v3/direction/transit/integrated}")
    private String amapTransitUrl;

    @Value("${amap.walk-url:https://restapi.amap.com/v3/direction/walking}")
    private String amapWalkUrl;

    @Value("${amap.bicycle-url:https://restapi.amap.com/v4/direction/bicycling}")
    private String amapBicycleUrl;

    @Value("${amap.place-url:https://restapi.amap.com/v3/place/text}")
    private String amapPlaceUrl;

    /** 周边检索（place/around）：按参考点圆形范围 + 距离排序，用于就近餐厅推荐 */
    @Value("${amap.around-url:https://restapi.amap.com/v3/place/around}")
    private String amapAroundUrl;

    @Value("${amap.qps-limit:50}")
    private int amapQpsLimit;

    @Value("${baidu.api-key:}")
    private String baiduApiKey;

    @Value("${baidu.api-secret:}")
    private String baiduApiSecret;

    @Value("${baidu.geocode-url:https://api.map.baidu.com/geocoding/v3}")
    private String baiduGeocodeUrl;

    @Value("${baidu.route-url:https://api.map.baidu.com/direction/v2/driving}")
    private String baiduRouteUrl;

    @Value("${baidu.transit-url:https://api.map.baidu.com/direction/v2/transit}")
    private String baiduTransitUrl;

    @Value("${baidu.qps-limit:30}")
    private int baiduQpsLimit;

    // Getters (单次定义)
    public String getAmapApiKey() { return amapApiKey; }
    public String getAmapApiSecret() { return amapApiSecret; }
    public String getAmapGeocodeUrl() { return amapGeocodeUrl; }
    public String getAmapRegeocodeUrl() { return amapRegeocodeUrl; }
    public String getAmapRouteUrl() { return amapRouteUrl; }
    public String getAmapTransitUrl() { return amapTransitUrl; }
    public String getAmapWalkUrl() { return amapWalkUrl; }
    public String getAmapBicycleUrl() { return amapBicycleUrl; }
    public String getAmapPlaceUrl() { return amapPlaceUrl; }
    public String getAmapAroundUrl() { return amapAroundUrl; }
    public int getAmapQpsLimit() { return amapQpsLimit; }

    public String getBaiduApiKey() { return baiduApiKey; }
    public String getBaiduApiSecret() { return baiduApiSecret; }
    public String getBaiduGeocodeUrl() { return baiduGeocodeUrl; }
    public String getBaiduRouteUrl() { return baiduRouteUrl; }
    public String getBaiduTransitUrl() { return baiduTransitUrl; }
    public int getBaiduQpsLimit() { return baiduQpsLimit; }

    @Bean
    public WebClient amapWebClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Bean
    public WebClient baiduWebClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
}