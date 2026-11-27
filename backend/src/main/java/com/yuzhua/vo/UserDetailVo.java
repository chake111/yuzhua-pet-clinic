package com.yuzhua.vo;

import com.yuzhua.entity.Pet;
import com.yuzhua.entity.SysUser;
import lombok.Data;

import java.util.List;

@Data
public class UserDetailVo {

    private SysUser user;
    private List<Pet> pets;
    private List<AppointmentVo> appointments;
}
