package com.yuzhua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhua.entity.Pet;
import com.yuzhua.vo.PetDisplayVo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

public interface PetMapper extends BaseMapper<Pet> {

    @Select("<script>"
            + "SELECT id, name, species, breed, avatar FROM pet WHERE "
            + "<choose>"
            + "<when test='ids != null and ids.size() > 0'>"
            + "id IN <foreach collection='ids' item='pid' open='(' separator=',' close=')'>#{pid}</foreach>"
            + "</when>"
            + "<otherwise>1 = 0</otherwise>"
            + "</choose>"
            + "</script>")
    List<PetDisplayVo> selectHistoryDisplay(@Param("ids") Collection<Long> ids);
}
