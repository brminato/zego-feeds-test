package dev.feed;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedStore {
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;

    public FeedStore(JdbcTemplate db, StringRedisTemplate redis) {
        this.db = db;
        this.redis = redis;
    }

    // One atomic replace, including an empty-feed marker. Versioned keys prevent rollback pollution.
    private static final DefaultRedisScript<Long> SNAPSHOT = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            redis.call('ZADD', KEYS[1], 0, '0')
            for i=1,#ARGV do redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i]) end
            redis.call('EXPIRE', KEYS[1], 86400)
            return #ARGV
            """, Long.class);

    void cache(long user, long version) {
        var ids = db.queryForList("SELECT article_id FROM feed_entry WHERE user_id=? ORDER BY article_id DESC LIMIT 1000", Long.class, user);
        redis.execute(SNAPSHOT, List.of(key(user, version)), ids.stream().map(String::valueOf).toArray());
    }

    static String key(long user, long version) {
        return "feed:{" + user + "}:" + version;
    }

    @Transactional
    public void rebuild(long user) {
        db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", Long.class, user);
        db.update("DELETE FROM feed_entry WHERE user_id=?", user);
        db.update("""
                INSERT INTO feed_entry(user_id,article_id)
                SELECT ?,a.id FROM article a WHERE EXISTS (
                 SELECT 1 FROM article_category ac JOIN subscription s ON s.category_id=ac.category_id
                 WHERE ac.article_id=a.id AND s.user_id=?) ORDER BY a.id DESC LIMIT 1000
                """, user, user);
        long v = db.queryForObject("UPDATE app_user SET feed_version=feed_version+1 WHERE id=? RETURNING feed_version", Long.class, user);
        cache(user, v);
    }

    @Transactional
    public List<Long> page(long user, long before, int limit) {
        long v = db.queryForObject("SELECT feed_version FROM app_user WHERE id=?", Long.class, user);
        String k = key(user, v);
        if (!Boolean.TRUE.equals(redis.hasKey(k))) {
            // Cache recovery uses the durable projection, never a category search.
            v = db.queryForObject("SELECT feed_version FROM app_user WHERE id=? FOR UPDATE", Long.class, user);
            k = key(user, v);
            cache(user, v);
        }
        var ids = redis.opsForZSet().reverseRangeByScore(k, 1, before - 1, 0, limit);
        return ids == null ? List.of() : ids.stream().map(Long::valueOf).toList();
    }
}
