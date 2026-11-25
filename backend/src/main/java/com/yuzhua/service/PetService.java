package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BatchLookup;
import com.yuzhua.common.BizException;
import com.yuzhua.common.PageResult;
import com.yuzhua.dto.PetForm;
import com.yuzhua.dto.PetQuery;
import com.yuzhua.entity.Pet;
import com.yuzhua.entity.SysUser;
import com.yuzhua.mapper.PetMapper;
import com.yuzhua.mapper.SysUserMapper;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.PetVo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PetService extends ServiceImpl<PetMapper, Pet> {

    private static final int MAX_PET_NAME_LEN = 20;
    private static final int MAX_PET_AGE_MONTH = 360;

    private final SysUserMapper sysUserMapper;

    public List<PetVo> listMine() {
        return list(Wrappers.<Pet>lambdaQuery()
                .eq(Pet::getUserId, UserContext.requireId())
                .orderByDesc(Pet::getCreateTime))
                .stream()
                .map(this::currentAge)
                .map(p -> toVo(p, null))
                .toList();
    }

    /** 事务内校验并创建当前登录用户的宠物档案，有生日时按生日推算月龄。 */
    @Transactional
    public PetVo create(PetForm form) {
        validate(form);
        Pet pet = new Pet();
        pet.setUserId(UserContext.requireId());
        pet.setName(form.getName());
        pet.setSpecies(form.getSpecies());
        pet.setBreed(form.getBreed());
        pet.setGender(form.getGender());
        pet.setBirthday(form.getBirthday());
        pet.setAgeMonth(deriveAgeMonth(form.getBirthday(), form.getAgeMonth()));
        pet.setWeight(form.getWeight());
        pet.setColor(form.getColor());
        pet.setIsNeutered(form.getIsNeutered());
        pet.setVaccineStatus(form.getVaccineStatus());
        pet.setDewormFlag(form.getDewormFlag());
        pet.setAvatar(form.getAvatar());
        pet.setAllergy(form.getAllergy());
        pet.setMedicalHistory(form.getMedicalHistory());
        pet.setRemark(form.getRemark());
        save(pet);
        return toVo(pet, null);
    }

    /** 校验本人归属后执行更新，支持生日与体重字段的三态显式清空。 */
    @Transactional
    public void update(Long id, PetForm form) {
        updateExisting(id, requireOwned(id), form);
    }

    /**
     * 逻辑删除本人宠物档案；历史预约、病历与问诊仍通过历史查询展示宠物末次信息，
     * 档案列表与新建下单不再可见。
     */
    @Transactional
    public void remove(Long id) {
        requireOwned(id);
        removeById(id);
    }

    public Pet requireOwned(Long id) {
        Pet pet = getById(id);
        if (pet == null || !Objects.equals(pet.getUserId(), UserContext.requireId())) {
            throw BizException.notFound("宠物档案");
        }
        return pet;
    }

    public List<PetVo> listByUser(Long userId) {
        return list(Wrappers.<Pet>lambdaQuery()
                .eq(Pet::getUserId, userId)
                .orderByDesc(Pet::getCreateTime))
                .stream()
                .map(this::currentAge)
                .map(p -> toVo(p, null))
                .toList();
    }

    public PageResult<PetVo> pageForAdmin(PetQuery q) {
        String kw = q.getKeyword() == null || q.getKeyword().isBlank() ? null : q.getKeyword().trim();
        LambdaQueryWrapper<Pet> qw = Wrappers.<Pet>lambdaQuery();
        if (kw != null) {
            List<Long> ownerIds = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                            .like(SysUser::getNickname, kw)
                            .or().like(SysUser::getPhone, kw))
                    .stream().map(SysUser::getId).toList();
            qw.and(w -> w.like(Pet::getName, kw).or(!ownerIds.isEmpty(), x -> x.in(Pet::getUserId, ownerIds)));
        }
        qw.orderByDesc(Pet::getCreateTime);
        Page<Pet> page = page(new Page<>(q.getPageNum(), q.getPageSize()), qw);

        Map<Long, SysUser> owners = BatchLookup.mapByIds(sysUserMapper::selectBatchIds,
                BatchLookup.ids(page.getRecords(), Pet::getUserId), SysUser::getId);
        List<PetVo> rows = page.getRecords().stream().map(p -> {
            currentAge(p);
            return toVo(p, owners.get(p.getUserId()));
        }).toList();
        return PageResult.of(page, rows);
    }

    /** 后台纠正档案：不校验归属，其余校验与用户端一致 */
    @Transactional
    public void updateByAdmin(Long id, PetForm form) {
        Pet own = getById(id);
        if (own == null) {
            throw BizException.notFound("宠物档案");
        }
        updateExisting(id, own, form);
    }

    private void updateExisting(Long id, Pet own, PetForm form) {
        validate(form);
        Pet row = new Pet();
        row.setId(id);
        row.setUserId(own.getUserId());
        row.setName(form.getName());
        row.setSpecies(form.getSpecies());
        row.setBreed(form.getBreed());
        row.setGender(form.getGender());
        row.setBirthday(form.getBirthday());
        row.setWeight(form.getWeight());
        row.setColor(form.getColor());
        row.setIsNeutered(form.getIsNeutered());
        row.setVaccineStatus(form.getVaccineStatus());
        row.setDewormFlag(form.getDewormFlag());
        row.setAvatar(form.getAvatar());
        row.setAllergy(form.getAllergy());
        row.setMedicalHistory(form.getMedicalHistory());
        row.setRemark(form.getRemark());
        LocalDate birthday = form.isBirthdaySubmitted() ? form.getBirthday() : own.getBirthday();
        row.setAgeMonth(deriveAgeMonth(birthday, form.getAgeMonth() == null ? own.getAgeMonth() : form.getAgeMonth()));
        // 原因: 省略字段交由 MP NOT_NULL 策略保留，仅显式提交的字段才写进 SET 以支持清空为 null
        update(row, Wrappers.<Pet>lambdaUpdate().eq(Pet::getId, id)
                .set(form.isBirthdaySubmitted(), Pet::getBirthday, form.getBirthday())
                .set(form.isWeightSubmitted(), Pet::getWeight, form.getWeight()));
    }

    /** 后台逻辑删除宠物档案（不校验本人归属）；历史业务单仍保留末次宠物展示信息。 */
    @Transactional
    public void removeByAdmin(Long id) {
        if (getById(id) == null) {
            throw BizException.notFound("宠物档案");
        }
        removeById(id);
    }

    private void validate(PetForm form) {
        if (form.getName() == null || form.getName().isBlank()) {
            throw new BizException("请填写宠物昵称");
        }
        if (form.getName().length() > MAX_PET_NAME_LEN) {
            throw new BizException("宠物昵称不超过 20 字");
        }
        if (form.getSpecies() == null || form.getSpecies().isBlank()) {
            throw new BizException("请选择宠物种类");
        }
    }

    static @Nullable Integer deriveAgeMonth(@Nullable LocalDate birthday, @Nullable Integer manualAge) {
        if (birthday == null) {
            if (manualAge != null && (manualAge < 0 || manualAge > MAX_PET_AGE_MONTH)) {
                throw new BizException("月龄须在 0～360 之间");
            }
            return manualAge;
        }
        if (birthday.isAfter(LocalDate.now())) {
            throw new BizException("出生日期不能晚于今天");
        }
        return (int) ChronoUnit.MONTHS.between(birthday, LocalDate.now());
    }

    private Pet currentAge(Pet pet) {
        pet.setAgeMonth(deriveAgeMonth(pet.getBirthday(), pet.getAgeMonth()));
        return pet;
    }

    public static PetVo toVo(Pet p, @Nullable SysUser owner) {
        return new PetVo(
                p.getId(),
                p.getUserId(),
                p.getName(),
                p.getSpecies(),
                p.getBreed(),
                p.getGender(),
                p.getBirthday(),
                p.getAgeMonth(),
                p.getWeight(),
                p.getColor(),
                p.getIsNeutered(),
                p.getVaccineStatus(),
                p.getDewormFlag(),
                p.getAvatar(),
                p.getAllergy(),
                p.getMedicalHistory(),
                p.getRemark(),
                p.getCreateTime(),
                owner == null ? null : owner.getNickname(),
                owner == null ? null : owner.getPhone()
        );
    }
}
