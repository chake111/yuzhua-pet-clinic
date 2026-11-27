package com.yuzhua.consult.ws;

import java.util.Map;

public interface ConsultPushGateway {

    void push(Long sessionId, Map<String, Object> payload);

    void pushModeration(Long sessionId, Map<String, Object> payload);
}
