package com.yuzhua.vo;

import com.yuzhua.entity.Pet;
import com.yuzhua.entity.SysUser;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Data
public class UserDetailVo {

    private @Nullable SysUser user;
    private @Nullable List<Pet> pets;
    private @Nullable List<AppointmentVo> appointments;
}
