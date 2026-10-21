package org.example.service.impl;

import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.example.service.DingTalkMessageDeduplicatorService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉消息幂等防重服务实现类
 *
 * @author zeymo
 */
@Slf4j
@Service
public class DingTalkMessageDeduplicatorServiceImpl implements DingTalkMessageDeduplicatorService {

    // 内存消息防重缓存：msgId -> 接收时间戳
    private final Map<String, Long> msgCache = new ConcurrentHashMap<>();

    /**
     * 判断消息是否重复
     * 如果该 msgId 首次出现，记录并返回 false；如果已存在且未过期，返回 true
     *
     * @param msgId 钉钉消息全局唯一ID
     * @return true-重复消息，false-首次接收的新消息
     */
    @Override
    public boolean isDuplicate(String msgId) {
        if (StringUtils.isBlank(msgId)) {
            return false;
        }

        long now = System.currentTimeMillis();
        // 清理超期消息缓存（防止内存占用过大）
        cleanExpiredIfNecessary(now);

        Long previousTimestamp = msgCache.putIfAbsent(msgId, now);
        if (previousTimestamp == null) {
            // 首次记录
            return false;
        }

        // 判断是否已超过 10 分钟防重窗口
        if (now - previousTimestamp > Constants.TEN_MINUTES_MILLIS) {
            msgCache.put(msgId, now);
            return false;
        }

        log.warn("检测到重复推送消息 msgId: [{}], 距首次处理已过 {} ms, 予以拦截忽略", msgId, now - previousTimestamp);
        return true;
    }

    /**
     * 当缓存数量超过上限时，清理已过期的历史消息
     *
     * @param now 当前时间戳
     */
    private void cleanExpiredIfNecessary(long now) {
        if (msgCache.size() <= Constants.THOUSAND) {
            return;
        }
        // 移除 10 分钟前的历史记录
        msgCache.entrySet().removeIf(entry -> now - entry.getValue() > Constants.TEN_MINUTES_MILLIS);
    }
}
