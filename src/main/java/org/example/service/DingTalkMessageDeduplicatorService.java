package org.example.service;

/**
 * 钉钉消息幂等防重服务接口
 *
 * @author zeymo
 */
public interface DingTalkMessageDeduplicatorService {

    /**
     * 判断消息是否重复
     * 如果该 msgId 首次出现，记录并返回 false；如果已存在（正在处理或已处理），返回 true
     *
     * @param msgId 钉钉消息全局唯一ID
     * @return true-重复消息，false-首次接收的新消息
     */
    boolean isDuplicate(String msgId);
}
