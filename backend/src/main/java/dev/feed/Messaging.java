package dev.feed;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
class Messaging {
    @Bean
    Declarables topology() {
        var exchange = new DirectExchange("feed.events", true, false);
        var dlx = new DirectExchange("feed.dead", true, false);
        var q = QueueBuilder.durable("feed.work").deadLetterExchange("feed.dead").deadLetterRoutingKey("dead").build();
        var dlq = QueueBuilder.durable("feed.dlq").build();
        return new Declarables(exchange, dlx, q, dlq, BindingBuilder.bind(q).to(exchange).with("work"), BindingBuilder.bind(dlq).to(dlx).with("dead"));
    }
}

@Service
@ConditionalOnProperty(name = "app.worker-enabled", havingValue = "true", matchIfMissing = true)
class OutboxPublisher {
    private final JdbcTemplate db;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;

    OutboxPublisher(JdbcTemplate db, RabbitTemplate rabbit, TransactionTemplate tx) {
        this.db = db;
        this.rabbit = rabbit;
        this.tx = tx;
    }

    @Scheduled(fixedDelay = 500)
    public void publish() {
        try {
            tx.executeWithoutResult(status -> {
                var ids = db.queryForList("SELECT id FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED", Long.class);
                for (long id : ids) {
                    var correlation = new CorrelationData(Long.toString(id));
                    var message = MessageBuilder.withBody(Long.toString(id).getBytes(StandardCharsets.UTF_8))
                            .setContentType("text/plain").setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
                    rabbit.send("feed.events", "work", message, correlation);
                    try {
                        var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                        if (!confirm.isAck() || correlation.getReturned() != null)
                            throw new IllegalStateException("Message unconfirmed/unroutable");
                    } catch (Exception e) {
                        throw new IllegalStateException("Publish failed; retrying outbox", e);
                    }
                    db.update("UPDATE outbox SET published_at=now() WHERE id=?", id);
                }
            });
        } catch (Exception e) {
            LoggerFactory.getLogger(getClass()).warn("Outbox retained for retry: {}", e.getMessage());
        }
    }
}

@Service
@ConditionalOnProperty(name = "app.worker-enabled", havingValue = "true", matchIfMissing = true)
class FeedWorker {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final FeedStore feeds;

    FeedWorker(JdbcTemplate db, TransactionTemplate tx, FeedStore feeds) {
        this.db = db;
        this.tx = tx;
        this.feeds = feeds;
    }

    @RabbitListener(queues = "feed.work")
    public void receive(String value) {
        process(Long.parseLong(value));
    }

    public void process(long id) {
        tx.executeWithoutResult(status -> {
            int fresh = db.update("INSERT INTO processed_event(event_id) VALUES (?) ON CONFLICT DO NOTHING", id);
            if (fresh == 0) return;
            var event = db.queryForMap("SELECT kind,aggregate_id FROM outbox WHERE id=?", id);
            long aggregate = ((Number) event.get("aggregate_id")).longValue();
            if ("ARTICLE".equals(event.get("kind"))) {
                // Fan-out to durable per-user tasks; includes prior members for category edits.
                db.update("""
                        INSERT INTO outbox(kind,aggregate_id)
                        SELECT 'USER',user_id FROM (
                          SELECT s.user_id FROM subscription s JOIN article_category ac ON ac.category_id=s.category_id WHERE ac.article_id=?
                          UNION SELECT user_id FROM feed_entry WHERE article_id=?
                        ) recipients
                        """, aggregate, aggregate);
            } else {
                feeds.rebuild(aggregate);
            }
        });
    }
}
