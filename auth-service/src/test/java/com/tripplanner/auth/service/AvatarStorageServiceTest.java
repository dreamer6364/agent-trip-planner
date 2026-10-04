package com.tripplanner.auth.service;

import com.tripplanner.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * 头像存储服务单元测试（纯本地临时目录，无外部依赖）
 */
class AvatarStorageServiceTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1};
    private static final byte[] JPG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};

    @TempDir
    Path tempDir;

    private AvatarStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new AvatarStorageService(tempDir.toString());
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    @Test
    @DisplayName("保存 PNG - 返回公开 URL 且文件按 UUID 落盘")
    void save_png_returnsUrlAndWritesFile() throws IOException {
        String url = storage.save(file("photo.png", PNG_BYTES));

        assertThat(url).startsWith(AvatarStorageService.URL_PREFIX).endsWith(".png");
        String filename = url.substring(AvatarStorageService.URL_PREFIX.length());
        assertThat(filename).matches("[0-9a-f\\-]{36}\\.png");
        assertThat(tempDir.resolve(filename)).exists();
        assertThat(Files.readAllBytes(tempDir.resolve(filename))).isEqualTo(PNG_BYTES);
    }

    @Test
    @DisplayName("保存 JPEG - 扩展名以魔数嗅探为准（无视客户端文件名）")
    void save_jpg_extensionFromMagicNotFilename() {
        String url = storage.save(file("evil.png", JPG_BYTES));
        assertThat(url).endsWith(".jpg");
    }

    @Test
    @DisplayName("保存非图片内容 - 抛 400 校验异常")
    void save_nonImage_rejected() {
        byte[] text = "not an image at all".getBytes();
        assertThatThrownBy(() -> storage.save(file("a.png", text)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getHttpStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("保存空文件 - 抛 400 校验异常")
    void save_emptyFile_rejected() {
        assertThatThrownBy(() -> storage.save(new MockMultipartFile("file", "a.png", "image/png", new byte[0])))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("保存超 5MB 文件 - 抛 400 校验异常")
    void save_oversize_rejected() {
        byte[] big = new byte[(int) AvatarStorageService.MAX_FILE_SIZE + 1];
        System.arraycopy(JPG_BYTES, 0, big, 0, JPG_BYTES.length);
        assertThatThrownBy(() -> storage.save(file("big.jpg", big)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getHttpStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("读取合法文件名 - 返回内容与正确 Content-Type")
    void load_validFilename_returnsBytes() {
        String url = storage.save(file("p.png", PNG_BYTES));
        String filename = url.substring(AvatarStorageService.URL_PREFIX.length());

        var loaded = storage.load(filename);
        assertThat(loaded).isNotNull();
        assertThat(loaded.bytes()).isEqualTo(PNG_BYTES);
        assertThat(loaded.contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("路径穿越/非白名单文件名 - 一律返回 null")
    void load_illegalFilenames_returnNull() {
        assertThat(storage.load("../../etc/passwd")).isNull();
        assertThat(storage.load("..\\..\\windows\\win.ini")).isNull();
        assertThat(storage.load("not-a-uuid.png")).isNull();
        assertThat(storage.load(UUID.randomUUID() + ".exe")).isNull();
        assertThat(storage.load(null)).isNull();
        // 合法 UUID 但文件不存在
        assertThat(storage.load(UUID.randomUUID() + ".png")).isNull();
    }

    @Test
    @DisplayName("deleteIfManaged - 删除本服务管理的旧头像文件")
    void deleteIfManaged_removesOwnFile() {
        String url = storage.save(file("old.png", PNG_BYTES));
        String filename = url.substring(AvatarStorageService.URL_PREFIX.length());
        assertThat(tempDir.resolve(filename)).exists();

        storage.deleteIfManaged(url);

        assertThat(tempDir.resolve(filename)).doesNotExist();
    }

    @Test
    @DisplayName("deleteIfManaged - 预设头像/外部 URL/穿越名 静默跳过")
    void deleteIfManaged_ignoresForeignAndTraversal() throws IOException {
        storage.deleteIfManaged("/avatars/fox.svg");
        storage.deleteIfManaged("https://example.com/x.png");
        storage.deleteIfManaged(AvatarStorageService.URL_PREFIX + "../../evil.png");
        storage.deleteIfManaged(null);

        assertThat(Files.list(tempDir)).isEmpty();
    }

    @Test
    @DisplayName("sniffExtension - 四种白名单格式识别 + 未知返回 null")
    void sniffExtension_recognizesWhitelist() {
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertThat(AvatarStorageService.sniffExtension(PNG_BYTES)).isEqualTo("png");
        assertThat(AvatarStorageService.sniffExtension(JPG_BYTES)).isEqualTo("jpg");
        assertThat(AvatarStorageService.sniffExtension(gif)).isEqualTo("gif");
        assertThat(AvatarStorageService.sniffExtension(webp)).isEqualTo("webp");
        assertThat(AvatarStorageService.sniffExtension(new byte[]{1, 2, 3})).isNull();
        assertThat(AvatarStorageService.sniffExtension(null)).isNull();
    }
}
