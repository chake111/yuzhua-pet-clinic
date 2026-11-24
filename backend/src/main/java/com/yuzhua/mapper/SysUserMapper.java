package com.yuzhua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhua.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0")
    SysUser selectForLogin(@Param("username") String username);

    @Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted = 0")
    SysUser selectWithPassword(@Param("id") Long id);
}
