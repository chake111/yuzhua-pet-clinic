package com.yuzhua.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuzhua.common.PageResult;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.entity.Appointment;
import com.yuzhua.entity.Doctor;
import com.yuzhua.entity.DoctorReview;
import com.yuzhua.entity.SysUser;
import com.yuzhua.mapper.AppointmentMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.mapper.DoctorReviewMapper;
import com.yuzhua.mapper.PetMapper;
import com.yuzhua.mapper.SysUserMapper;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.PetDisplayVo;
import com.yuzhua.vo.ReviewVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台评价的宠物展示必须与预约、病历、问诊同口径：宠物档案逻辑删除后，
 * 评价仍能显示当时的宠物名与种类。这里用代理 mapper 断言调用的是历史展示查询，
 * 而不是会过滤 deleted=1 的普通批量查询。
 */
class DoctorReviewHistoryTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), DoctorReview.class);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @SuppressWarnings("unchecked")
    static <T> T mapper(Class<T> type, BiFunction<String, Object[], Object> result) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> result.apply(method.getName(), args));
    }

    /** 代理 mapper 的调用痕迹放在独立 holder 里：super(...) 之前不能引用 this 的实例字段 */
    static class Holder {
        Collection<Long> historyIds;
        boolean petBatchCalled;
    }

    static class Service extends DoctorReviewService {
        List<DoctorReview> rows = List.of();
        final Holder holder;

        Service(Holder holder) {
            super(
                    mapper(SysUserMapper.class, (name, args) -> List.of(user())),
                    null,
                    mapper(DoctorMapper.class, (name, args) -> List.of(doctor())),
                    mapper(AppointmentMapper.class, (name, args) -> List.of(appointment())),
                    mapper(PetMapper.class, (name, args) -> {
                        if ("selectHistoryDisplay".equals(name)) {
                            holder.historyIds = (Collection<Long>) args[0];
                            return List.of(pet());
                        }
                        if ("selectBatchIds".equals(name)) {
                            holder.petBatchCalled = true;
                        }
                        return List.of();
                    }));
            this.holder = holder;
        }

        @Override
        public <E extends IPage<DoctorReview>> E page(E page, Wrapper<DoctorReview> query) {
            page.setRecords(rows);
            page.setTotal(rows.size());
            return page;
        }
    }

    static SysUser user() {
        SysUser u = new SysUser();
        u.setId(1L);
        u.setNickname("张三");
        return u;
    }

    static Doctor doctor() {
        Doctor d = new Doctor();
        d.setId(5L);
        d.setName("周医生");
        return d;
    }

    static Appointment appointment() {
        Appointment a = new Appointment();
        a.setId(11L);
        a.setOrderNo("PC202610010001");
        a.setPetId(2L);
        return a;
    }

    static PetDisplayVo pet() {
        return new PetDisplayVo(2L, "奶糖", "猫", null, null);
    }

    static DoctorReview review() {
        DoctorReview r = new DoctorReview();
        r.setId(1L);
        r.setAppointmentId(11L);
        r.setUserId(1L);
        r.setDoctorId(5L);
        r.setRating(5);
        r.setIsAnonymous(0);
        return r;
    }

    @Test
    void staffReviewPageUsesHistoryPetDisplay() {
        Holder holder = new Holder();
        Service s = new Service(holder);
        s.rows = List.of(review());

        LoginUser admin = new LoginUser();
        admin.setId(99L);
        admin.setTyp(TokenType.ADMIN);
        admin.setRole(UserRole.ADMIN);
        UserContext.set(admin);

        PageResult<ReviewVo> page = s.pageForStaff(null, null, new PageQuery());
        assertEquals(1, page.getRecords().size());
        ReviewVo vo = page.getRecords().get(0);
        assertEquals("奶糖", vo.petName());
        assertEquals("猫", vo.petSpecies());
        assertEquals("PC202610010001", vo.orderNo());
        assertTrue(holder.historyIds.contains(2L), "评价应走 selectHistoryDisplay");
        assertFalse(holder.petBatchCalled, "不应退回过滤逻辑删除的 selectBatchIds");
    }
}
