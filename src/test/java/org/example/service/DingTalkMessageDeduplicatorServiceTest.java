package org.example.service;

import org.example.service.impl.DingTalkMessageDeduplicatorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉钉消息幂等防重测试
 *
 * @author zeymo
 */
public class DingTalkMessageDeduplicatorServiceTest {

    private DingTalkMessageDeduplicatorService deduplicatorService;

    @BeforeEach
    public void setup() {
        deduplicatorService = new DingTalkMessageDeduplicatorServiceImpl();
    }

    /**
     * 测试首次接收与重复接收消息去重
     */
    @Test
    public void testIsDuplicate() {
        String msgId1 = "msg_abc_001";
        String msgId2 = "msg_abc_002";

        // 首次出现返回 false
        assertFalse(deduplicatorService.isDuplicate(msgId1));
        assertFalse(deduplicatorService.isDuplicate(msgId2));

        // 重复推送相同的 msgId 返回 true
        assertTrue(deduplicatorService.isDuplicate(msgId1));
        assertTrue(deduplicatorService.isDuplicate(msgId2));

        // 空值或空白字符返回 false
        assertFalse(deduplicatorService.isDuplicate(null));
        assertFalse(deduplicatorService.isDuplicate(""));
    }
}
