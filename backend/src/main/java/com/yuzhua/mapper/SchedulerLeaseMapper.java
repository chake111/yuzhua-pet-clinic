package com.yuzhua.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SchedulerLeaseMapper {

    @Insert("INSERT IGNORE INTO scheduler_lease (name, holder, locked_until, locked_at) "
            + "VALUES (#{name}, #{holder}, DATE_ADD(NOW(3), INTERVAL #{ttlSeconds} SECOND), NOW(3))")
    int insertIfAbsent(@Param("name") String name, @Param("holder") String holder,
                       @Param("ttlSeconds") long ttlSeconds);

    @Update("UPDATE scheduler_lease SET holder = #{holder}, "
            + "locked_until = DATE_ADD(NOW(3), INTERVAL #{ttlSeconds} SECOND), locked_at = NOW(3) "
            + "WHERE name = #{name} AND locked_until <= NOW(3)")
    int takeOverExpired(@Param("name") String name, @Param("holder") String holder,
                        @Param("ttlSeconds") long ttlSeconds);

    @Update("UPDATE scheduler_lease SET locked_until = NOW(3) "
            + "WHERE name = #{name} AND holder = #{holder}")
    int release(@Param("name") String name, @Param("holder") String holder);
}