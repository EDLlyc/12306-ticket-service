package com.ahu.ticket.config;

import com.ahu.ticket.service.TrainBloomFilterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TrainBloomFilterInitializer implements ApplicationRunner {

    private final TrainBloomFilterService trainBloomFilterService;

    public TrainBloomFilterInitializer(TrainBloomFilterService trainBloomFilterService) {
        this.trainBloomFilterService = trainBloomFilterService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            TrainBloomFilterService.RebuildResult result = trainBloomFilterService.rebuildFromDatabase();
            log.info("应用启动完成车次 BloomFilter 全量重建: trainCount={}, expectedInsertions={}, falseProbability={}",
                    result.trainCount(), result.expectedInsertions(), result.falseProbability());
        } catch (Exception e) {
            log.error("应用启动时重建车次 BloomFilter 失败，查询链路将退化为不使用布隆过滤器拦截。", e);
        }
    }
}
