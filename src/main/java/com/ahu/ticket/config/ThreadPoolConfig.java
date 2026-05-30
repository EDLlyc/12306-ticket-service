package com.ahu.ticket.config;

import com.ahu.ticket.common.TraceIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 全局异步线程池配置
 * 面试考核点: 
 * 1. 为什么不用 Executors.newFixedThreadPool? (默认使用无界的 LinkedBlockingQueue，高并发下导致 OOM)
 * 2. 为什么不用 naked thread? (创建销毁开销大，无法复用，无法控制最大并发数)
 * 3. 拒绝策略(CallerRunsPolicy)的设计理念
 */
@Slf4j
@EnableAsync
@Configuration
public class ThreadPoolConfig {

    public static final String SSE_EXECUTOR = "sseAsyncExecutor";

    @Bean(name = SSE_EXECUTOR)
    public Executor sseAsyncExecutor() {
        log.info("【线程池初始化】开始初始化 SSE 异步流式输出守护线程池...");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数：根据业务特性(IO密集型还是CPU密集型)设定，这里举例4
        executor.setCorePoolSize(4);
        
        // 最大线程数：缓冲突发流量
        executor.setMaxPoolSize(10);
        
        // 阻塞队列：【关键防御】杜绝无界队列(Integer.MAX_VALUE)导致的 OOM，这里限制为 100
        executor.setQueueCapacity(100);
        
        // 线程存活时间
        executor.setKeepAliveSeconds(60);
        
        // 线程前缀名，方便排查日志和 jstack 链路跟踪
        executor.setThreadNamePrefix("sse-async-");
        executor.setTaskDecorator(mdcTaskDecorator());

        // 【面试亮点】：配置为 Daemon 守护线程，跟随主线程(JVM)退出而自动退出，不阻塞应用停机
        executor.setDaemon(true);

        // 拒绝策略：【关键防御】当队列(100)满且线程(10)满时，怎么处理新来的请求？
        // CallerRunsPolicy：由调用者所在的线程(Tomcat工作线程)去执行，起到降压缓冲的作用，绝不丢弃一个任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 执行初始化
        executor.initialize();
        return executor;
    }

    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (contextMap != null) {
                        MDC.setContextMap(contextMap);
                    } else {
                        MDC.clear();
                    }
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
