package com.yuzhua.controller.client;

import com.yuzhua.common.Result;
import com.yuzhua.dto.PetForm;
import com.yuzhua.entity.Pet;
import com.yuzhua.service.PetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "用户端-宠物档案")
@RestController
@RequestMapping("/client/pet")
@RequiredArgsConstructor
public class PetController {

    private final PetService petService;

    @Operation(summary = "我的宠物列表")
    @GetMapping
    public Result<List<Pet>> list() {
        return Result.ok(petService.listMine());
    }

    @Operation(summary = "新增宠物")
    @PostMapping
    public Result<Pet> create(@RequestBody PetForm req) {
        return Result.ok("已保存", petService.create(req));
    }

    @Operation(summary = "修改宠物")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody PetForm req) {
        petService.update(id, req);
        return Result.ok("已保存", null);
    }

    @Operation(summary = "删除宠物")
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        petService.remove(id);
        return Result.ok("已删除", null);
    }
}
