package com.yuzhua.websocket;

import com.yuzhua.domain.enums.ConsultModerationStatus;
import com.yuzhua.entity.ConsultSession;
import com.yuzhua.mapper.ConsultSessionMapper;
import com.yuzhua.security.AccountGuard;
import com.yuzhua.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出站帧发送前的准入校验。
 *
 * 入站帧和握手已有校验，但推送是按连接登记表里的旧房间订阅广播的：
 * 账号被停用、令牌到期、角色/医生绑定变化后，静默保持的旧连接仍会收到业务内容。
 * 所以在真正写帧之前按接收方再查一次数据库。
 *
 * 用 REQUIRES_NEW 的只读事务而不是复用调用方事务：REST 侧的推送注册在 afterCommit，
 * 此时外层事务已提交但资源尚未清理；独立事务能稳定读到提交后的新状态。
 * 依赖方向固定为 ConsultService → ConsultPush → ConsultDeliveryGuard → AccountGuard/Mapper，
 * 本类不反向依赖 Service，避免与 ConsultService 形成循环。
 */
@Component
@RequiredArgsConstructor
public class ConsultDeliveryGuard {

    public enum Decision {
        OK,
        ACCOUNT_INVALID,
        SESSION_DENIED
    }

    private final AccountGuard accountGuard;
    private final ConsultSessionMapper sessionMapper;

    /**
     * 业务帧：账号有效、会话存在、接收方仍是会话当事人、会话未被处置。
     * 正常结束（{@code ConsultSessionStatus.CLOSED}）的会话仍放行，历史查询与收尾通知不受影响。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Decision checkBusiness(LoginUser receiver, Long sessionId) {
        if (!accountGuard.active(receiver)) {
            return Decision.ACCOUNT_INVALID;
        }
        if (sessionId == null) {
            return Decision.OK;
        }
        ConsultSession s = sessionMapper.selectById(sessionId);
        if (s == null || !isMember(receiver, s)) {
            return Decision.SESSION_DENIED;
        }
        if (s.getModerateStatus() != null && s.getModerateStatus() != ConsultModerationStatus.NORMAL) {
            return Decision.SESSION_DENIED;
        }
        return Decision.OK;
    }

    /**
     * 处置通知：只要求账号有效且仍是原参与方。
     * 处置刚提交时会话已是非正常态，不能套用 {@link #checkBusiness}，否则通知本身发不出去。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Decision checkParticipant(LoginUser receiver, Long sessionId) {
        if (!accountGuard.active(receiver)) {
            return Decision.ACCOUNT_INVALID;
        }
        if (sessionId == null) {
            return Decision.OK;
        }
        ConsultSession s = sessionMapper.selectById(sessionId);
        return s != null && isMember(receiver, s) ? Decision.OK : Decision.SESSION_DENIED;
    }

    /** 控制帧（READY / PONG / 普通 ERROR）：只核对账号与令牌 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Decision checkAccount(LoginUser receiver) {
        return accountGuard.active(receiver) ? Decision.OK : Decision.ACCOUNT_INVALID;
    }

    private static boolean isMember(LoginUser u, ConsultSession s) {
        return (u.isClient() && s.getUserId() != null && s.getUserId().equals(u.getId()))
                || (u.isDoctor() && u.getDoctorId() != null && s.getDoctorId() != null
                && s.getDoctorId().equals(u.getDoctorId()));
    }
}
