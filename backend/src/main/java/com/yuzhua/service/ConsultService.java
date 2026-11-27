package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BizException;
import com.yuzhua.common.BatchLookup;
import com.yuzhua.common.PageResult;
import com.yuzhua.domain.enums.ConsultModerationStatus;
import com.yuzhua.domain.enums.ConsultMsgType;
import com.yuzhua.domain.enums.ConsultSenderRole;
import com.yuzhua.domain.enums.ConsultSessionStatus;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.dto.ConsultForm;
import com.yuzhua.dto.ConsultMessageQuery;
import com.yuzhua.dto.ConsultQuery;
import com.yuzhua.entity.ConsultMessage;
import com.yuzhua.entity.ConsultSession;
import com.yuzhua.entity.Doctor;
import com.yuzhua.entity.Pet;
import com.yuzhua.entity.SysUser;
import com.yuzhua.mapper.ConsultMessageMapper;
import com.yuzhua.mapper.ConsultSessionMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.mapper.PetMapper;
import com.yuzhua.mapper.SysUserMapper;
import com.yuzhua.security.LoginUser;
import com.yuzhua.consult.ws.ConsultPushGateway;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.ConsultSessionVo;
import com.yuzhua.vo.PetDisplayVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ConsultService extends ServiceImpl<ConsultSessionMapper, ConsultSession> {

    private static final int MAX_TEXT_LEN = 1000;
    private static final int MAX_SUGGEST_LEN = 500;
    private static final int PREVIEW_LEN = 100;
    private static final int RECENT_MAX = 50;
    private static final int MAX_IMAGE_URL_LEN = 255;
    private static final int MAX_REASON_LEN = 255;

    private final ConsultMessageMapper messageMapper;
    private final ConsultPushGateway consultPush;
    private final PetService petService;
    private final PetMapper petMapper;
    private final DoctorMapper doctorMapper;
    private final SysUserMapper sysUserMapper;

    /**
     * 校验宠物归属、医生存在且在诊（{@code EnableStatus.ENABLED}）及症状非空白后创建免费图文问诊会话（初始待接诊，未读数归零）。
     * 本接口无幂等键去重，重复提交会创建独立会话。
     */
    @Transactional
    public ConsultSessionVo create(ConsultForm req) {
        if (req.getPetId() == null) {
            throw new BizException("请选择问诊宠物");
        }
        Pet pet = petService.requireOwned(req.getPetId());
        if (req.getDoctorId() == null) {
            throw new BizException("请选择医生");
        }
        Doctor doctor = doctorMapper.selectById(req.getDoctorId());
        if (doctor == null) {
            throw BizException.notFound("医生");
        }
        if (doctor.getStatus() != EnableStatus.ENABLED) {
            throw new BizException("该医生已停诊，请换个医生");
        }
        String symptom = req.getSymptom();
        if (symptom == null || symptom.isBlank()) {
            throw new BizException("请描述症状");
        }
        ConsultSession s = new ConsultSession();
        s.setUserId(pet.getUserId());
        s.setDoctorId(doctor.getId());
        s.setPetId(pet.getId());
        s.setSymptom(symptom.trim());
        s.setImages(blankToNull(req.getImages()));
        s.setStatus(ConsultSessionStatus.PENDING);
        s.setUserUnread(0);
        s.setDoctorUnread(0);
        save(s);
        // 原因: 新建时宠物档案必未删除，直接复用刚校验的实体构建展示摘要；历史列表再走含已删档案查询
        PetDisplayVo brief = new PetDisplayVo();
        BeanUtils.copyProperties(pet, brief);
        return buildVo(s, false, brief, doctor, null);
    }

    /**
     * 医生接诊。先锁会话行再判断状态，和发送/结束/处置共用同一把锁；
     * 条件更新仍保留，锁外被其他路径改动时不会误覆盖。
     */
    @Transactional
    public void accept(Long sessionId) {
        ConsultSession s = lockMember(sessionId);
        if (!UserContext.require().isDoctor()) {
            throw BizException.forbidden("只有接诊医生能点接诊");
        }
        if (s.getStatus() != ConsultSessionStatus.PENDING) {
            throw new BizException("这个会话不在待接诊状态");
        }
        Long sid = Objects.requireNonNull(s.getId());
        boolean won = update(Wrappers.<ConsultSession>lambdaUpdate()
                .set(ConsultSession::getStatus, ConsultSessionStatus.ACTIVE)
                .set(ConsultSession::getStartTime, LocalDateTime.now())
                .eq(ConsultSession::getId, sid)
                .eq(ConsultSession::getStatus, ConsultSessionStatus.PENDING));
        if (!won) {
            throw new BizException("这个会话已被接诊，请刷新");
        }
        ConsultMessage sys = systemMessage(sid, "医生已接诊，会话开始。");
        consultPush.push(sid, sessionFrame(sid, ConsultSessionStatus.ACTIVE, null, sys));
    }

    /**
     * 结束会话。医生随时可结束，并可附一段问诊小结（线上只建议、确诊要面诊）；
     * 患者只能关掉自己那张还没被接诊的会话，且不能写小结。
     * 先锁会话行：与发送共用一个顺序，结束提示一定落在已提交消息之后，摘要不会被旧消息覆盖。
     */
    @Transactional
    public void close(Long sessionId, @Nullable String summary) {
        ConsultSession s = lockMember(sessionId);
        boolean byDoctor = UserContext.require().isDoctor();
        if (s.getStatus() == ConsultSessionStatus.CLOSED) {
            throw new BizException("会话已经结束了");
        }
        if (!byDoctor && s.getStatus() == ConsultSessionStatus.ACTIVE) {
            throw new BizException("医生已接诊，等医生结束本次问诊");
        }
        String concl = byDoctor ? blankToNull(summary) : null;
        if (concl != null && concl.length() > MAX_SUGGEST_LEN) {
            throw new BizException("问诊小结不超过 500 字");
        }
        Long sid = Objects.requireNonNull(s.getId());
        boolean won = update(Wrappers.<ConsultSession>lambdaUpdate()
                .set(ConsultSession::getStatus, ConsultSessionStatus.CLOSED)
                .set(ConsultSession::getEndTime, LocalDateTime.now())
                .set(concl != null, ConsultSession::getSummary, concl)
                .eq(ConsultSession::getId, sid)
                .eq(ConsultSession::getStatus, s.getStatus()));
        if (!won) {
            throw new BizException("会话状态刚刚变化，请刷新后再试");
        }
        ConsultMessage sys = systemMessage(sid, byDoctor ? "医生已结束本次问诊。" : "用户已关闭本次问诊。");
        consultPush.push(sid, sessionFrame(sid, ConsultSessionStatus.CLOSED, concl, sys));
    }

    public ConsultSession requireMember(Long sessionId) {
        return requireMember(getById(sessionId));
    }

    private ConsultSession requireMember(@Nullable ConsultSession s) {
        if (s == null) {
            throw BizException.notFound("问诊会话");
        }
        LoginUser u = UserContext.require();
        boolean member = (u.isClient() && Objects.equals(s.getUserId(), u.getId()))
                || (u.isDoctor() && u.getDoctorId() != null && Objects.equals(s.getDoctorId(), u.getDoctorId()));
        if (!member) {
            throw BizException.forbidden("只有患者本人和接诊医生能进入这个会话");
        }
        // 原因: 被处置的会话对参与方视同不存在，与列表过滤口径一致；管理员审计走 historyForViewer
        if (s.getModerateStatus() != null && s.getModerateStatus() != ConsultModerationStatus.NORMAL) {
            throw BizException.notFound("问诊会话");
        }
        return s;
    }

    private ConsultSession lockMember(Long sessionId) {
        return requireMember(baseMapper.selectByIdForUpdate(sessionId));
    }

    private ConsultSession lockSession(Long sessionId) {
        ConsultSession s = baseMapper.selectByIdForUpdate(sessionId);
        if (s == null) {
            throw BizException.notFound("问诊会话");
        }
        return s;
    }

    public ConsultSessionVo detailMine(Long id) {
        ConsultSession s = requireMember(id);
        return buildVos(List.of(s), UserContext.require().isDoctor()).get(0);
    }

    public PageResult<ConsultSessionVo> pageMine(ConsultQuery q) {
        LoginUser u = UserContext.require();
        if (!u.isClient() && !u.isDoctor()) {
            throw BizException.forbidden("管理员账号不参与问诊会话");
        }
        // 原因: 条件式 eq 第三参会在调用前求值，不能在患者分支触发 requireDoctorId() 抛 403
        Long mineAsDoctor = u.isDoctor() ? UserContext.requireDoctorId() : null;
        LambdaQueryWrapper<ConsultSession> qw = Wrappers.<ConsultSession>lambdaQuery()
                .eq(u.isClient(), ConsultSession::getUserId, u.getId())
                .eq(mineAsDoctor != null, ConsultSession::getDoctorId, mineAsDoctor)
                // 原因: 被管理员处置的会话对参与方不可见，避免出现无法点开的未读红点
                .eq(ConsultSession::getModerateStatus, ConsultModerationStatus.NORMAL)
                .eq(q.getStatus() != null, ConsultSession::getStatus, q.getStatus())
                .last("ORDER BY COALESCE(last_message_time, create_time) DESC, id DESC");
        Page<ConsultSession> page = page(new Page<>(q.getPageNum(), q.getPageSize()), qw);
        return PageResult.of(page, buildVos(page.getRecords(), u.isDoctor()));
    }

    public PageResult<ConsultSessionVo> pageForAdmin(ConsultQuery q) {
        Page<ConsultSession> page = page(new Page<>(q.getPageNum(), q.getPageSize()),
                Wrappers.<ConsultSession>lambdaQuery()
                        .eq(q.getStatus() != null, ConsultSession::getStatus, q.getStatus())
                        .eq(q.getModerateStatus() != null, ConsultSession::getModerateStatus, q.getModerateStatus())
                        .orderByDesc(ConsultSession::getLastMessageTime)
                        .orderByDesc(ConsultSession::getId));
        return PageResult.of(page, buildVos(page.getRecords(), false));
    }

    private List<ConsultSessionVo> buildVos(List<ConsultSession> rows, boolean asDoctor) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, PetDisplayVo> pets = BatchLookup.mapByIds(petMapper::selectHistoryDisplay, BatchLookup.ids(rows, ConsultSession::getPetId), PetDisplayVo::getId);
        Map<Long, Doctor> doctors = BatchLookup.mapByIds(doctorMapper::selectBatchIds, BatchLookup.ids(rows, ConsultSession::getDoctorId), Doctor::getId);
        Map<Long, SysUser> users = BatchLookup.mapByIds(sysUserMapper::selectBatchIds, BatchLookup.ids(rows, ConsultSession::getUserId), SysUser::getId);
        List<ConsultSessionVo> out = new ArrayList<>(rows.size());
        for (ConsultSession s : rows) {
            out.add(buildVo(s, asDoctor, pets.get(s.getPetId()), doctors.get(s.getDoctorId()), users.get(s.getUserId())));
        }
        return out;
    }

    public PageResult<ConsultMessage> history(Long sessionId, ConsultMessageQuery q) {
        requireMember(sessionId);
        return pageHistory(sessionId, q, false);
    }

    public PageResult<ConsultMessage> historyForViewer(Long sessionId, ConsultMessageQuery q) {
        boolean admin = UserContext.require().isAdmin();
        if (admin) {
            if (getById(sessionId) == null) {
                throw BizException.notFound("问诊会话");
            }
        } else {
            requireMember(sessionId);
        }
        return pageHistory(sessionId, q, admin);
    }

    private PageResult<ConsultMessage> pageHistory(Long sessionId, ConsultMessageQuery q, boolean includeHidden) {
        Page<ConsultMessage> page = messageMapper.selectPage(new Page<>(q.getPageNum(), q.getPageSize()),
                Wrappers.<ConsultMessage>lambdaQuery()
                        .eq(ConsultMessage::getSessionId, sessionId)
                        .lt(q.getBeforeId() != null, ConsultMessage::getId, q.getBeforeId())
                        // 原因: 管理员审计视图保留已屏蔽消息便于合规追溯，参与方视图过滤屏蔽行
                        .eq(!includeHidden, ConsultMessage::getHidden, ConsultMessage.HIDE_NO)
                        .orderByDesc(ConsultMessage::getId));
        return PageResult.of(page);
    }

    public List<ConsultMessage> recent(Long sessionId, int limit) {
        int n = Math.min(Math.max(limit, 1), RECENT_MAX);
        List<ConsultMessage> rows = messageMapper.selectList(Wrappers.<ConsultMessage>lambdaQuery()
                .eq(ConsultMessage::getSessionId, sessionId)
                .eq(ConsultMessage::getHidden, ConsultMessage.HIDE_NO)
                .orderByDesc(ConsultMessage::getId)
                .last("LIMIT " + n));
        Collections.reverse(rows);
        return rows;
    }

    /**
     * 在会话行锁内落库消息并累加对侧未读数与最后消息摘要。
     * 清零仅由 {@link #markRead} 在同锁内完成，避免在线推送丢帧导致红点永久丢失。
     */
    @Transactional
    public ConsultMessage send(Long sessionId, @Nullable String msgType, @Nullable String content) {
        ConsultSession s = lockMember(sessionId);
        if (s.getStatus() == ConsultSessionStatus.CLOSED) {
            throw new BizException("问诊已结束，会话已关闭");
        }
        LoginUser u = UserContext.require();
        boolean byDoctor = u.isDoctor();
        ConsultMsgType type = normalizeMessageType(msgType, byDoctor);
        String text = normalizeMessageContent(type, content);
        Long sid = Objects.requireNonNull(s.getId());
        ConsultMessage m = buildMessage(sid, u, byDoctor, type, text);
        messageMapper.insert(m);
        baseMapper.touchOnMessage(sid, preview(type, text), byDoctor, true);
        return m;
    }

    private ConsultMsgType normalizeMessageType(@Nullable String msgType, boolean byDoctor) {
        String raw = msgType == null || msgType.isBlank() ? ConsultMsgType.TEXT.code() : msgType.trim().toUpperCase();
        ConsultMsgType type;
        try {
            type = ConsultMsgType.fromCode(raw);
        } catch (IllegalArgumentException e) {
            throw new BizException("消息类型只支持 TEXT、IMAGE 或 SUGGEST");
        }
        if (type == ConsultMsgType.SYSTEM) {
            throw new BizException("消息类型只支持 TEXT、IMAGE 或 SUGGEST");
        }
        if (type == ConsultMsgType.SUGGEST && !byDoctor) {
            throw new BizException("只有医生能发起到院建议");
        }
        return type;
    }

    private String normalizeMessageContent(ConsultMsgType type, @Nullable String content) {
        String text = content == null ? "" : content.trim();
        // 原因: 到院建议卡片本身已表达就医意图，允许附言为空；其余类型不允许空消息
        if (text.isEmpty() && type != ConsultMsgType.SUGGEST) {
            throw new BizException("消息内容不能为空");
        }
        if (type == ConsultMsgType.TEXT && text.length() > MAX_TEXT_LEN) {
            throw new BizException("单条消息不超过 " + MAX_TEXT_LEN + " 字");
        }
        if (type == ConsultMsgType.IMAGE && text.length() > MAX_IMAGE_URL_LEN) {
            throw new BizException("图片地址过长");
        }
        if (type == ConsultMsgType.SUGGEST && text.length() > MAX_SUGGEST_LEN) {
            throw new BizException("到院建议不超过 " + MAX_SUGGEST_LEN + " 字");
        }
        return text;
    }

    private ConsultMessage buildMessage(Long sessionId, LoginUser u, boolean byDoctor, ConsultMsgType type, String text) {
        ConsultMessage m = new ConsultMessage();
        m.setSessionId(sessionId);
        // 原因: 后台账号 id 与医生 id 独立编号，消息发送者统一存医生侧业务 id 以对齐会话归属
        m.setSenderId(byDoctor ? u.getDoctorId() : u.getId());
        m.setSenderRole(byDoctor ? ConsultSenderRole.DOCTOR : ConsultSenderRole.USER);
        m.setSenderName(u.getName());
        m.setSenderAvatar(u.getAvatar());
        m.setMsgType(type);
        m.setContent(text);
        m.setIsRead(0);
        return m;
    }

    /** 我读过这个会话了：先锁会话，再锁定对侧可见未读消息按精确ID标记，角标清零；返回本次实际标记的消息ID */
    @Transactional
    public List<Long> markRead(Long sessionId) {
        ConsultSession s = lockMember(sessionId);
        Long sid = Objects.requireNonNull(s.getId());
        boolean doctor = UserContext.require().isDoctor();
        ConsultSenderRole role = doctor ? ConsultSenderRole.DOCTOR : ConsultSenderRole.USER;
        List<Long> ids = messageMapper.unreadPeerIdsForUpdate(sid, role);
        for (int from = 0; from < ids.size(); from += 200) {
            List<Long> batch = ids.subList(from, Math.min(from + 200, ids.size()));
            int changed = messageMapper.markPeerRead(sid, role, batch);
            if (changed != batch.size()) {
                throw new IllegalStateException("已读消息更新数量不一致");
            }
        }
        if (doctor) {
            baseMapper.clearDoctorUnread(sid);
        } else {
            baseMapper.clearUserUnread(sid);
        }
        return ids;
    }

    public long unreadTotal() {
        LoginUser u = UserContext.require();
        if (!u.isClient() && !u.isDoctor()) {
            return 0;
        }
        Long mineAsDoctor = u.isDoctor() ? UserContext.requireDoctorId() : null;
        return count(Wrappers.<ConsultSession>lambdaQuery()
                .eq(u.isClient(), ConsultSession::getUserId, u.getId())
                .eq(mineAsDoctor != null, ConsultSession::getDoctorId, mineAsDoctor)
                // 原因: 被处置的会话对参与方不可见，须同步从红点计数排除
                .eq(ConsultSession::getModerateStatus, ConsultModerationStatus.NORMAL)
                .gt(u.isClient(), ConsultSession::getUserUnread, 0)
                .gt(u.isDoctor(), ConsultSession::getDoctorUnread, 0));
    }

    /**
     * 管理员屏蔽/恢复消息，仅改 hidden 标志并重算会话摘要与未读角标（锁顺序：会话行 -> 消息行）；
     * 不修改会话诊疗状态与已读标志。
     */
    @Transactional
    public void changeMessageHidden(Long messageId, @Nullable Integer hidden, @Nullable String reason) {
        if (hidden == null || (hidden != ConsultMessage.HIDE_NO && hidden != ConsultMessage.HIDE_YES)) {
            throw new BizException("无法完成消息操作，请刷新后重试");
        }
        // 原因: 先用普通读定位所属会话，再严格按“会话行 -> 消息行”加锁防止与 send/markRead 成环
        ConsultMessage found = messageMapper.selectById(messageId);
        if (found == null) {
            throw BizException.notFound("问诊消息");
        }
        lockSession(Objects.requireNonNull(found.getSessionId()));
        // 原因: 持会话锁后重新当前读消息行，防止并发管理员基于旧快照覆盖标志位
        ConsultMessage m = messageMapper.selectByIdForUpdate(messageId);
        if (m == null) {
            throw BizException.notFound("问诊消息");
        }
        boolean hide = hidden == ConsultMessage.HIDE_YES;
        String why = blankToNull(reason);
        if (hide && why == null) {
            throw new BizException("请填写屏蔽原因");
        }
        if (why != null && why.length() > MAX_REASON_LEN) {
            throw new BizException("屏蔽原因不超过 255 字");
        }
        messageMapper.update(null, Wrappers.<ConsultMessage>lambdaUpdate()
                .set(ConsultMessage::getHidden, hidden)
                .set(ConsultMessage::getHiddenReason, hide ? why : null)
                .set(ConsultMessage::getHiddenBy, hide ? UserContext.requireId() : null)
                .set(ConsultMessage::getHiddenTime, hide ? LocalDateTime.now() : null)
                .eq(ConsultMessage::getId, messageId));
        Long sid = Objects.requireNonNull(m.getSessionId());
        // 原因: 被屏蔽或恢复的消息可能是最后一条或未读消息，须按剩余可见消息重算摘要与未读
        refreshSessionAggregates(sid);
        m.setHidden(hidden);
        m.setHiddenReason(null);
        m.setHiddenBy(null);
        m.setHiddenTime(null);
        consultPush.push(sid, Map.of("type", "MESSAGE_VISIBILITY", "sessionId", sid,
                "messageId", messageId, "hidden", hidden, "message", hidden == 0 ? m : Map.of()));
    }

    /**
     * 管理员处置会话（{@code ConsultModerationStatus.INVALID} 无效关闭 / {@code VIOLATION} 违规屏蔽 / {@code NORMAL} 撤销处置；{@code null} 抛业务异常），持会话行锁执行。
     * 非 {@code NORMAL} 处置连带关闭未结束会话并由 {@link ConsultPush#pushModeration} 清退房间订阅；撤销不恢复关闭态。
     */
    @Transactional
    public void moderate(Long sessionId, @Nullable ConsultModerationStatus moderateStatus, @Nullable String reason) {
        if (moderateStatus == null) {
            throw new BizException("无法完成会话处置，请刷新后重试");
        }
        // 原因: 处置与发送/结束共用会话行锁，保证处置提交时无在途消息写入
        ConsultSession s = lockSession(sessionId);
        if (moderateStatus == ConsultModerationStatus.NORMAL) {
            // 原因: 撤销处置仅清空处置标志，已连带关闭的会话保持关闭态
            update(Wrappers.<ConsultSession>lambdaUpdate()
                    .set(ConsultSession::getModerateStatus, ConsultModerationStatus.NORMAL)
                    .set(ConsultSession::getModerateReason, null)
                    .set(ConsultSession::getModerateBy, null)
                    .set(ConsultSession::getModerateTime, null)
                    .eq(ConsultSession::getId, sessionId));
            consultPush.push(sessionId, Map.of("type", "MODERATION", "sessionId", sessionId, "moderateStatus", 0));
            return;
        }
        String why = blankToNull(reason);
        if (why == null) {
            throw new BizException("请填写处置原因");
        }
        if (why.length() > MAX_REASON_LEN) {
            throw new BizException("处置原因不超过 255 字");
        }
        boolean closeIt = s.getStatus() != ConsultSessionStatus.CLOSED;
        boolean won = update(Wrappers.<ConsultSession>lambdaUpdate()
                .set(ConsultSession::getModerateStatus, moderateStatus)
                .set(ConsultSession::getModerateReason, why)
                .set(ConsultSession::getModerateBy, UserContext.requireId())
                .set(ConsultSession::getModerateTime, LocalDateTime.now())
                .set(closeIt, ConsultSession::getStatus, ConsultSessionStatus.CLOSED)
                .set(closeIt, ConsultSession::getEndTime, LocalDateTime.now())
                .eq(ConsultSession::getId, sessionId)
                .eq(closeIt, ConsultSession::getStatus, s.getStatus()));
        if (!won) {
            throw new BizException("会话状态刚刚变化，请刷新后再试");
        }
        if (closeIt) {
            // 原因: 处置关闭后的系统消息仅管理员审计视图可见，参与方已无法读取整个会话
            systemMessage(sessionId, "该会话已由管理员处置关闭。");
        }
        // 原因: 提交后先推送 MODERATION 帧再清退房间订阅，阻止旧连接继续收到正文
        consultPush.pushModeration(sessionId, Map.of(
                "type", "MODERATION", "sessionId", sessionId, "moderateStatus", moderateStatus.code()));
    }

    private void refreshSessionAggregates(Long sessionId) {
        baseMapper.refreshUnread(sessionId);
        // 原因: changeMessageHidden 锁会话前已执行普通 SELECT，此处须用 FOR UPDATE 当前读最新可见消息以重算末条摘要
        ConsultMessage last = messageMapper.selectLastVisibleForUpdate(sessionId);
        if (last == null) {
            baseMapper.setLastMessage(sessionId, null, null);
        } else {
            ConsultMsgType msgType = last.getMsgType() == null ? ConsultMsgType.TEXT : last.getMsgType();
            baseMapper.setLastMessage(sessionId,
                    preview(msgType, last.getContent() == null ? "" : last.getContent()),
                    last.getCreateTime());
        }
    }

    public void markDoctorOnline(Long doctorId, boolean online) {
        doctorMapper.update(null, Wrappers.<Doctor>lambdaUpdate()
                .setSql(online ? "online_status = IF(status = 1, 1, 0)" : "online_status = 0")
                .eq(Doctor::getId, doctorId));
    }

    @PostConstruct
    void resetDoctorOnline() {
        doctorMapper.update(null, Wrappers.<Doctor>lambdaUpdate()
                .set(Doctor::getOnlineStatus, 0)
                .eq(Doctor::getOnlineStatus, 1));
    }

    private ConsultMessage systemMessage(Long sessionId, String text) {
        ConsultMessage m = new ConsultMessage();
        m.setSessionId(sessionId);
        m.setSenderId(0L);
        m.setSenderRole(ConsultSenderRole.SYSTEM);
        m.setSenderName("系统");
        m.setMsgType(ConsultMsgType.SYSTEM);
        m.setContent(text);
        m.setIsRead(1);
        messageMapper.insert(m);
        baseMapper.touchOnMessage(sessionId, text, false, false);
        return m;
    }

    private Map<String, Object> sessionFrame(Long sessionId, ConsultSessionStatus status,
                                             @Nullable String summary, ConsultMessage message) {
        Map<String, Object> frame = new HashMap<>();
        frame.put("type", "SESSION");
        frame.put("sessionId", sessionId);
        frame.put("status", status.code());
        frame.put("summary", summary == null ? "" : summary);
        frame.put("message", message);
        return frame;
    }

    private ConsultSessionVo buildVo(ConsultSession s, boolean asDoctor,
                                   @Nullable PetDisplayVo pet, @Nullable Doctor doctor, @Nullable SysUser patient) {
        ConsultSessionVo vo = new ConsultSessionVo();
        BeanUtils.copyProperties(s, vo);
        if (pet != null) {
            vo.setPetName(pet.getName());
        }
        if (doctor != null) {
            vo.setDoctorName(doctor.getName());
            vo.setDoctorAvatar(doctor.getAvatar());
        }
        if (patient != null) {
            vo.setUserNickname(patient.getNickname());
            vo.setUserPhone(patient.getPhone());
        }
        vo.setMyUnread(asDoctor ? s.getDoctorUnread() : s.getUserUnread());
        return vo;
    }

    private String preview(ConsultMsgType type, String text) {
        if (type == ConsultMsgType.IMAGE) {
            return "[图片]";
        }
        if (type == ConsultMsgType.SUGGEST) {
            String hint = "[建议到院就医]";
            return text.isEmpty() ? hint : hint + " " + clip(text);
        }
        return clip(text);
    }

    private static String clip(String text) {
        return text.length() <= PREVIEW_LEN ? text : text.substring(0, PREVIEW_LEN) + "…";
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }


}
