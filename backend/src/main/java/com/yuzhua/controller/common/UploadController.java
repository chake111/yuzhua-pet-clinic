package com.yuzhua.controller.common;

import com.yuzhua.common.Result;
import com.yuzhua.service.FileStorageService;
import com.yuzhua.vo.UploadVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "公共-上传")
@RestController
@RequestMapping("/common/upload")
@RequiredArgsConstructor
public class UploadController {

    private final FileStorageService fileStorageService;

    @Operation(summary = "上传单张图片，返回可访问 URL")
    @PostMapping
    public Result<UploadVo> upload(@RequestParam("file") MultipartFile file) {
        String url = fileStorageService.store(file);
        return Result.ok(new UploadVo(url));
    }
}
