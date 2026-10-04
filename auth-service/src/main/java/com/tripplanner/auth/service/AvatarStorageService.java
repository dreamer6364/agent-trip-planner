package com.tripplanner.auth.service;

import com.tripplanner.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 头像文件存储服务（本地磁盘）
 *
 * <p>安全约束：文件名由服务端 UUID 生成（客户端文件名不落盘），
 * 读取/删除仅接受 {@link #FILENAME_PATTERN} 白名单（UUID + 图片扩展名），
 * 解析后必须仍在存储根目录内（防路径穿越）；格式以魔数嗅探为准（防伪造 Content-Type）。</p>
 */
@Slf4j
@Service
public class AvatarStorageService {

    /** 头像 URL 前缀（对外访问路径，经网关公开路由到本服务） */
    public static final String URL_PREFIX = "/api/auth/avatars/";

    /** 单文件大小上限（前端已压缩至 256px，5MB 为宽松兜底） */
    public static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    static final Pattern FILENAME_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpe?g|webp|gif)$");

    private final Path root;

    public AvatarStorageService(@Value("${app.storage.avatar-dir:}") String dir) {
        String effective = (dir == null || dir.isBlank())
                ? Paths.get(System.getProperty("user.home"), ".tripforge", "avatars").toString()
                : dir;
        this.root = Paths.get(effective).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("头像存储目录创建失败: " + this.root, e);
        }
        log.info("头像存储目录: {}", this.root);
    }

    /** 已嗅探出的头像文件内容 */
    public record AvatarFile(byte[] bytes, String contentType) {
    }

    /**
     * 保存上传的头像文件
     *
     * @param file 上传文件（内容经魔数校验）
     * @return 对外访问 URL（{@code /api/auth/avatars/{uuid}.{ext}}）
     * @throws BizException 文件为空 / 超限 / 非白名单图片格式（400），写盘失败（500）
     */
    public String save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.validationError("头像文件不能为空", null);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw BizException.validationError("头像文件不能超过 5MB", null);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BizException.validationError("头像文件读取失败", null);
        }
        String ext = sniffExtension(bytes);
        if (ext == null) {
            throw BizException.validationError("仅支持 PNG/JPEG/WebP/GIF 图片格式", null);
        }
        String filename = UUID.randomUUID() + "." + ext;
        try {
            Files.write(root.resolve(filename), bytes);
        } catch (IOException e) {
            log.error("头像写盘失败: {}", filename, e);
            throw new BizException("AVATAR_SAVE_FAILED", "头像保存失败，请稍后重试", 500);
        }
        log.info("头像已保存: {} ({} bytes)", filename, bytes.length);
        return URL_PREFIX + filename;
    }

    /**
     * 读取头像文件（供公开 GET 端点使用）
     *
     * @param filename 路径变量文件名
     * @return 文件内容；文件名不合法或不存在时返回 {@code null}（由调用方转 404）
     */
    public AvatarFile load(String filename) {
        if (filename == null || !FILENAME_PATTERN.matcher(filename).matches()) {
            return null;
        }
        Path path = root.resolve(filename).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return new AvatarFile(Files.readAllBytes(path), contentTypeOf(filename));
        } catch (IOException e) {
            log.warn("头像读取失败: {}", filename, e);
            return null;
        }
    }

    /**
     * 删除本服务管理的旧头像文件（仅当 URL 形如 {@code /api/auth/avatars/{uuid}.{ext}} 时）。
     * 预设头像（/avatars/*.svg）与外部 URL 不在管理范围内，静默跳过。
     */
    public void deleteIfManaged(String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith(URL_PREFIX)) {
            return;
        }
        String filename = avatarUrl.substring(URL_PREFIX.length());
        if (!FILENAME_PATTERN.matcher(filename).matches()) {
            return;
        }
        try {
            if (Files.deleteIfExists(root.resolve(filename))) {
                log.info("旧头像已删除: {}", filename);
            }
        } catch (IOException e) {
            log.warn("旧头像删除失败: {}", filename, e);
        }
    }

    /**
     * 按魔数嗅探图片格式（不受客户端 Content-Type/文件名欺骗）
     *
     * @return png / jpg / gif / webp；无法识别返回 {@code null}
     */
    static String sniffExtension(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "gif";
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static String contentTypeOf(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
