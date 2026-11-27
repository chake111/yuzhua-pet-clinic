package com.yuzhua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhua.entity.AdminUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AdminUserMapper extends BaseMapper<AdminUser> {

    @Select("SELECT * FROM admin_user WHERE username = #{username} AND deleted = 0")
    AdminUser selectForLogin(@Param("username") String username);

    @Select("SELECT * FROM admin_user WHERE id = #{id} AND deleted = 0")
    AdminUser selectWithPassword(@Param("id") Long id);
}
