package org.example.config;

import com.ct.common.core.constant.Constants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置类
 *
 * @author zeymo
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * 钉钉机器人群聊消息异步处理线程池
     *
     * @return 线程池执行器
     */
    @Bean(name = "botMessageExecutor")
    public ThreadPoolTaskExecutor botMessageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数
        executor.setCorePoolSize(Constants.EIGHT);
        // 最大线程数
        executor.setMaxPoolSize(Constants.SIXTEEN);
        // 阻塞队列容量
        executor.setQueueCapacity(Constants.TWO_HUNDRED);
        // 空闲线程存活秒数
        executor.setKeepAliveSeconds(Constants.SIXTY);
        // 线程名前缀
        executor.setThreadNamePrefix("dingtalk-bot-async-");
        // 拒绝策略：队列满且线程达上限时，由调用线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
