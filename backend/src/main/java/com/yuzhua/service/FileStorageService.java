package com.yuzhua.service;

import com.yuzhua.common.BizException;
import com.yuzhua.props.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int IMAGE_HEADER_READ_BYTES = 12;
    private static final int UNSIGNED_BYTE_MASK = 0xff;
    private static final int JPEG_MAGIC_LEN = 3;
    private static final int JPEG_SOI_0 = 0xff;
    private static final int JPEG_SOI_1 = 0xd8;
    private static final int WEBP_TAG_OFFSET = 8;

    private final AppProperties props;

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        if (file.getSize() > props.getUpload().getMaxSizeBytes()) {
            throw new BizException("文件不能超过 " + props.getUpload().getMaxSizeBytes() / BYTES_PER_MB + "MB");
        }
        String ext = extractSafeExt(file.getOriginalFilename());
        requireImageHeader(file, ext);

        String subDir = LocalDate.now().format(DIR_FMT);
        Path base = Paths.get(props.getUpload().getDir()).toAbsolutePath().normalize();
        Path targetDir = base.resolve(subDir).normalize();
        // 原因: 纵深防御路径穿越，即便扩展名已通过白名单仍确认目标目录位于 base 之内
        if (!targetDir.startsWith(base)) {
            log.warn("上传路径越界，已拒绝: {}", targetDir);
            throw new BizException("文件上传失败，请稍后重试");
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        try {
            Files.createDirectories(targetDir);
            file.transferTo(targetDir.resolve(filename));
        } catch (IOException e) {
            log.error("文件写入失败", e);
            throw new BizException(500, "文件上传失败，请重试");
        }
        return props.getUpload().getUrlPrefix() + "/" + subDir + "/" + filename;
    }

    private void requireImageHeader(MultipartFile file, String ext) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(IMAGE_HEADER_READ_BYTES);
            String text = new String(header, StandardCharsets.ISO_8859_1);
            boolean matches = switch (ext) {
                case "jpg", "jpeg" -> header.length >= JPEG_MAGIC_LEN
                        && (header[0] & UNSIGNED_BYTE_MASK) == JPEG_SOI_0
                        && (header[1] & UNSIGNED_BYTE_MASK) == JPEG_SOI_1
                        && (header[2] & UNSIGNED_BYTE_MASK) == JPEG_SOI_0;
                case "png" -> text.startsWith("\u0089PNG\r\n\u001a\n");
                case "gif" -> text.startsWith("GIF87a") || text.startsWith("GIF89a");
                case "webp" -> header.length == IMAGE_HEADER_READ_BYTES
                        && text.startsWith("RIFF") && text.substring(WEBP_TAG_OFFSET).equals("WEBP");
                default -> false;
            };
            if (!matches) {
                throw new BizException("图片内容与文件格式不符，请重新选择图片");
            }
        } catch (IOException e) {
            throw new BizException("无法读取图片，请重新选择");
        }
    }

    private String extractSafeExt(@Nullable String originalName) {
        if (originalName == null) {
            throw new BizException("无法识别文件类型");
        }
        int slash = Math.max(originalName.lastIndexOf('/'), originalName.lastIndexOf('\\'));
        String name = slash >= 0 ? originalName.substring(slash + 1) : originalName;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new BizException("文件缺少扩展名");
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException("仅支持 " + String.join("/", ALLOWED_EXT) + " 格式图片");
        }
        return ext;
    }
}
