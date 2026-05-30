package com.ahu.ticket.service;

import com.ahu.ticket.entity.Train;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TrainBloomFilterService {

    public static final String TRAIN_NUMBER_BLOOM_FILTER_KEY = "bloom:train_numbers";

    private final RedissonClient redissonClient;
    private final ITrainService trainService;
    private final long minimumExpectedInsertions;
    private final double falseProbability;

    public TrainBloomFilterService(RedissonClient redissonClient,
                                   ITrainService trainService,
                                   @Value("${app.cache.train-bloom.expected-insertions:100000}") long minimumExpectedInsertions,
                                   @Value("${app.cache.train-bloom.false-probability:0.03}") double falseProbability) {
        this.redissonClient = redissonClient;
        this.trainService = trainService;
        this.minimumExpectedInsertions = minimumExpectedInsertions;
        this.falseProbability = falseProbability;
    }

    public boolean mightContainTrainNumber(String trainNumber) {
        RBloomFilter<String> bloomFilter = getBloomFilter();
        return !bloomFilter.isExists() || bloomFilter.contains(trainNumber);
    }

    public void addTrainNumber(String trainNumber) {
        if (trainNumber == null || trainNumber.isBlank()) {
            return;
        }
        RBloomFilter<String> bloomFilter = getBloomFilter();
        if (!bloomFilter.isExists()) {
            bloomFilter.tryInit(minimumExpectedInsertions, falseProbability);
        }
        bloomFilter.add(trainNumber);
    }

    public RebuildResult rebuildFromDatabase() {
        List<String> trainNumbers = trainService.list().stream()
                .map(Train::getTrainNumber)
                .filter(trainNumber -> trainNumber != null && !trainNumber.isBlank())
                .distinct()
                .collect(Collectors.toList());

        RBloomFilter<String> bloomFilter = getBloomFilter();
        boolean deleted = bloomFilter.delete();
        long expectedInsertions = Math.max(minimumExpectedInsertions, Math.max(1, trainNumbers.size()));
        boolean initialized = bloomFilter.tryInit(expectedInsertions, falseProbability);
        long insertedCount = trainNumbers.isEmpty() ? 0 : bloomFilter.add(trainNumbers);

        log.info("车次 BloomFilter 已重建: deletedOld={}, initialized={}, expectedInsertions={}, falseProbability={}, insertedCount={}",
                deleted, initialized, expectedInsertions, falseProbability, insertedCount);
        return new RebuildResult(trainNumbers.size(), expectedInsertions, falseProbability, deleted, initialized);
    }

    private RBloomFilter<String> getBloomFilter() {
        return redissonClient.getBloomFilter(TRAIN_NUMBER_BLOOM_FILTER_KEY);
    }

    public record RebuildResult(int trainCount,
                                long expectedInsertions,
                                double falseProbability,
                                boolean deletedOldFilter,
                                boolean initialized) {
    }
}
