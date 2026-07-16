package com.digixmed.icu.viform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * MongoDB 自增主键生成服务。
 *
 * <p>通过 {@code findAndModify + $inc} 在 sequence 集合中原子获取下一个序列值，
 * 保证多实例并发安全。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequenceGeneratorService {

    /** SmartCare 主库模板 */
    private final MongoTemplate smartCareMongoTemplate;

    /**
     * 获取指定序列名的下一个自增值。
     *
     * <p>首次调用时自动创建序列（upsert），初始值为 1。</p>
     *
     * @param seqName 序列名，如 "bloodSugar"
     * @return 下一个自增值
     */
    public Long nextSeq(String seqName) {
        Query query = new Query(Criteria.where("_id").is(seqName));
        Update update = new Update().inc("seq", 1);
        FindAndModifyOptions options = FindAndModifyOptions.options()
                .upsert(true)
                .returnNew(true);

        Object result = smartCareMongoTemplate.findAndModify(
                query, update, options, Object.class, "sequence");

        if (result == null) {
            log.error("[Sequence] findAndModify 返回 null，序列名={}", seqName);
            throw new IllegalStateException("无法获取自增序列: " + seqName);
        }

        // findAndModify 返回的文档中取出 seq 值
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) result;
        Object seqObj = map.get("seq");
        Long seq = seqObj instanceof Integer ? ((Integer) seqObj).longValue()
                : (Long) seqObj;

        log.debug("[Sequence] 获取序列 {} -> {}", seqName, seq);
        return seq;
    }
}
