package com.tripplanner.trip.service;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.trip.dto.response.CoverResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行程封面图搜索服务
 *
 * <p>按「城市 + 景点」关键词到公开图片搜索引擎检索封面图，返回浏览器可直出的图片直链。
 * 关键词结果做两级缓存（Redis 命中缓存 / 失败缓存）与并发去重，避免首页批量出卡时重复打外部接口。</p>
 *
 * <p>安全约束：</p>
 * <ul>
 *   <li>关键词归一化（去控制字符、长度上限）</li>
 *   <li>图片直链仅放行百度系域名（防 SSRF）</li>
 *   <li>外部请求 8 秒超时，任何异常降级为「无封面」，不影响行程列表</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoverImageService {

    /** 图片搜索接口（返回 JSON，UTF-8 编码，免 key） */
    private static final String SEARCH_ENDPOINT = "https://image.baidu.com/search/acjson";

    /** 图片直链域名白名单（防 SSRF：只允许搜索引擎自身 CDN） */
    private static final Set<String> ALLOWED_HOST_SUFFIXES = Set.of(".baidu.com", ".bdimg.com", ".bdstatic.com");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Content-Type 中的 charset 提取 */
    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*([\\w\\-]+)", Pattern.CASE_INSENSITIVE);

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    /** 关键词长度上限 */
    private static final int MAX_KEYWORD_LENGTH = 60;
    /** 结果数量上限 */
    private static final int RESULT_LIMIT = 30;
    /** 过滤过小图片（避免卡片糊图） */
    private static final int MIN_WIDTH = 400;
    private static final int MIN_HEIGHT = 300;
    /** 卡片为横版，优先横图（宽/高） */
    private static final double PREFERRED_ASPECT_RATIO = 1.2;

    private static final Duration CACHE_TTL = Duration.ofDays(7);
    private static final Duration MISS_CACHE_TTL = Duration.ofHours(1);
    /** 反爬/网络抖动等临时失败的短时缓存 */
    private static final Duration SHORT_MISS_TTL = Duration.ofMinutes(2);
    private static final String MISS_MARKER = "__MISS__";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 同一关键词的并发搜索去重 */
    private final ConcurrentMap<String, CompletableFuture<CoverResponse>> inFlight = new ConcurrentHashMap<>();

    /**
     * 按关键词搜索封面图
     *
     * @param keyword 城市/景点等关键词
     * @return 封面图信息；无结果或搜索失败返回 {@code null}（调用方回退默认渐变底图）
     */
    public CoverResponse searchCover(String keyword) {
        String kw = normalize(keyword);
        if (kw.isEmpty()) {
            log.info("封面图关键词为空，跳过搜索");
            return null;
        }

        String cached = readRawCache(kw);
        if (cached != null) {
            if (MISS_MARKER.equals(cached)) {
                // 失败缓存：1 小时内不重复打外部接口
                log.info("封面图命中失败缓存 keyword={}", kw);
                return null;
            }
            try {
                CoverResponse cover = objectMapper.readValue(cached, CoverResponse.class);
                log.info("封面图命中缓存 keyword={}", kw);
                return cover;
            } catch (Exception e) {
                log.debug("封面图缓存反序列化失败 keyword={}, 原因: {}", kw, e.getMessage());
            }
        }

        CompletableFuture<CoverResponse> mine = new CompletableFuture<>();
        CompletableFuture<CoverResponse> existing = inFlight.putIfAbsent(kw, mine);
        if (existing != null) {
            try {
                return existing.get(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.debug("等待封面图搜索结果超时 keyword={}", kw);
                return null;
            }
        }

        try {
            SearchResult result = doSearch(kw);
            if (result.cover() != null) {
                writeCache(kw, result.cover());
            } else {
                // 临时失败（反爬/网络抖动）只做短时失败缓存，避免首页高频重试，同时不长期毒化关键词
                writeMissCache(kw, result.transientFailure() ? SHORT_MISS_TTL : MISS_CACHE_TTL);
            }
            mine.complete(result.cover());
            return result.cover();
        } catch (Exception e) {
            mine.complete(null);
            return null;
        } finally {
            inFlight.remove(kw, mine);
        }
    }

    /**
     * 搜索结果：transientFailure=true 表示外部限流/反爬等临时失败，不写失败缓存，下个请求可重试
     */
    private record SearchResult(CoverResponse cover, boolean transientFailure) {}

    private SearchResult doSearch(String kw) {
        try {
            String url = SEARCH_ENDPOINT
                    + "?tn=resultjson_com&word=" + URLEncoder.encode(kw, StandardCharsets.UTF_8)
                    + "&pn=0&rn=" + RESULT_LIMIT + "&ie=utf-8&oe=utf-8";

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://image.baidu.com/")
                    .header("Accept", "application/json, text/plain, */*")
                    // 缺少 Accept-Language 会被搜索引擎判定为爬虫直接拒绝
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .GET()
                    .build();

            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.warn("封面图搜索接口返回异常 status={}, keyword={}", response.statusCode(), kw);
                return new SearchResult(null, true);
            }

            String body = decodeBody(response);
            if (body.contains("Forbid spider access") || !body.contains("\"data\"")) {
                log.warn("封面图搜索被反爬拦截 keyword={}", kw);
                return new SearchResult(null, true);
            }

            CoverResponse cover;
            try {
                cover = pickBest(sanitize(body), kw);
            } catch (Exception e) {
                // 搜索结果偶发非法转义（如 \'），解析失败时用正则兜底，避免整体失败
                log.warn("封面图结果解析失败，走正则兜底 keyword={}, 原因: {}", kw, e.getMessage());
                cover = pickBestByRegex(body, kw);
            }
            if (cover == null) {
                log.info("封面图搜索无可用结果 keyword={}", kw);
                return new SearchResult(null, false);
            }
            log.info("封面图搜索成功 keyword={}, url={}", kw, cover.getUrl());
            return new SearchResult(cover, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("封面图搜索被中断 keyword={}", kw);
            return new SearchResult(null, true);
        } catch (Exception e) {
            log.warn("封面图搜索失败 keyword={}, 原因: {}", kw, e.getMessage());
            return new SearchResult(null, true);
        }
    }

    /**
     * 响应体解码：优先 Content-Type 中的 charset，其次严格 UTF-8，失败回落 GBK
     */
    private static String decodeBody(HttpResponse<byte[]> response) {
        byte[] bytes = response.body();
        String contentType = response.headers().firstValue("content-type").orElse("");
        Matcher matcher = CHARSET_PATTERN.matcher(contentType);
        if (matcher.find()) {
            try {
                return new String(bytes, Charset.forName(matcher.group(1).trim()));
            } catch (Exception ignore) {
                // charset 不可识别时按 UTF-8/GBK 探测
            }
        }
        try {
            return UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /**
     * 从搜索结果中挑选最合适的封面：优先横版大图，其次回退第一张合格图
     */
    private CoverResponse pickBest(String body, String kw) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            return null;
        }

        CoverResponse fallback = null;
        for (JsonNode item : data) {
            if (item == null || item.isMissingNode() || item.isNull()) {
                continue;
            }
            String image = firstNonEmpty(
                    text(item, "middleURL"),
                    text(item, "hoverURL"),
                    text(item, "thumbURL")
            );
            if (image == null || !isAllowedImageUrl(image)) {
                continue;
            }

            int width = item.path("width").asInt(0);
            int height = item.path("height").asInt(0);
            if (width < MIN_WIDTH || height < MIN_HEIGHT) {
                continue;
            }

            CoverResponse candidate = CoverResponse.builder()
                    .keyword(kw)
                    .url(image)
                    .title(stripHtml(firstNonEmpty(
                            text(item, "fromPageTitleEnc"),
                            text(item, "replaceTitle"),
                            text(item, "middleAge")))
                    )
                    .width(width)
                    .height(height)
                    .source("baidu")
                    .build();

            if (width / (double) height >= PREFERRED_ASPECT_RATIO) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /**
     * 搜索结果文本清洗：搜索引擎偶发输出 JSON 规范外的转义（如 \'），此处归一后交由 Jackson 解析
     */
    private static String sanitize(String body) {
        return body.replace("\\'", "'");
    }

    /**
     * Jackson 解析失败时的兜底：直接正则抽取可用图片直链
     */
    private CoverResponse pickBestByRegex(String body, String kw) {
        Matcher matcher = Pattern.compile("\"(?:middleURL|hoverURL|thumbURL)\":\"(https?://[^\"]+)\"")
                .matcher(body);
        while (matcher.find()) {
            String url = matcher.group(1).replace("\\/", "/");
            if (isAllowedImageUrl(url)) {
                return CoverResponse.builder().keyword(kw).url(url).source("baidu").build();
            }
        }
        return null;
    }

    /**
     * 图片直链校验：仅 http(s) 且域名在白名单内，阻断 SSRF
     */
    private boolean isAllowedImageUrl(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return false;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            if (lower.equals("baidu.com") || lower.equals("bdimg.com") || lower.equals("bdstatic.com")) {
                return true;
            }
            for (String suffix : ALLOWED_HOST_SUFFIXES) {
                if (lower.endsWith(suffix)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String normalize(String keyword) {
        if (keyword == null) {
            return "";
        }
        String cleaned = CONTROL_CHARS.matcher(keyword).replaceAll(" ");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ").trim();
        if (cleaned.length() > MAX_KEYWORD_LENGTH) {
            cleaned = cleaned.substring(0, MAX_KEYWORD_LENGTH).trim();
        }
        return cleaned;
    }

    private String cacheKey(String kw) {
        return "trip:cover:" + SecureUtil.md5(kw);
    }

    private String readRawCache(String kw) {
        try {
            return redisTemplate.opsForValue().get(cacheKey(kw));
        } catch (Exception e) {
            log.debug("封面图缓存读取失败 keyword={}, 原因: {}", kw, e.getMessage());
            return null;
        }
    }

    private void writeCache(String kw, CoverResponse cover) {
        try {
            redisTemplate.opsForValue().set(cacheKey(kw), objectMapper.writeValueAsString(cover), CACHE_TTL);
        } catch (Exception e) {
            log.warn("封面图缓存写入失败 keyword={}, 原因: {}", kw, e.getMessage());
        }
    }

    private void writeMissCache(String kw, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(cacheKey(kw), MISS_MARKER, ttl);
        } catch (Exception e) {
            log.warn("封面图失败缓存写入失败 keyword={}, 原因: {}", kw, e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String stripHtml(String value) {
        if (value == null) {
            return null;
        }
        String plain = value.replaceAll("<[^>]*>", "").trim();
        return plain.isBlank() ? null : plain;
    }
}
