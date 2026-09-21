package dev.feed;

import java.util.*;
import java.security.Principal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class Api {
    private final JdbcTemplate db;
    private final FeedStore feeds;
    private final PasswordEncoder passwords;

    public Api(JdbcTemplate db, FeedStore feeds, PasswordEncoder passwords) {
        this.db = db;
        this.feeds = feeds;
        this.passwords = passwords;
    }

    public enum Role { READER, WRITER }

    public record Register(@Email @NotBlank @Size(max = 200) String email, @NotBlank @Size(max = 100) String name,
                           @Size(min = 10, max = 60) @NotNull String password, Role role) {
    }

    public record Categories(@NotNull @Size(max = 10) Set<@NotNull @Min(1) @Max(10) Long> categoryIds) {
    }

    public record ArticleInput(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 50000) String body,
                               @NotEmpty @Size(max = 10) Set<@NotNull @Min(1) @Max(10) Long> categoryIds) {
    }

    long user(Principal p) {
        return db.queryForObject("SELECT id FROM app_user WHERE email=?", Long.class, p.getName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> register(@Valid @RequestBody Register r) {
        if (r.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds BCrypt byte limit");
        Role role = r.role() == null ? Role.READER : r.role();
        long id = db.queryForObject("INSERT INTO app_user(email,display_name,password_hash,role) VALUES (?,?,?,?) RETURNING id", Long.class, r.email().toLowerCase(Locale.ROOT), r.name(), passwords.encode(r.password()), role.name());
        return Map.of("id", id, "email", r.email().toLowerCase(Locale.ROOT), "role", role);
    }

    @GetMapping("/me")
    public Map<String, Object> me(Principal p) {
        return db.queryForMap("SELECT id,email,display_name,role,feed_version FROM app_user WHERE id=?", user(p));
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return db.queryForList("SELECT * FROM category ORDER BY id");
    }

    @GetMapping("/subscriptions")
    public List<Long> subscriptions(Principal p) {
        return db.queryForList("SELECT category_id FROM subscription WHERE user_id=? ORDER BY category_id", Long.class, user(p));
    }

    @PutMapping("/subscriptions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public Map<String, String> subscribe(Principal p, @Valid @RequestBody Categories input) {
        long u = user(p);
        db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", Long.class, u);
        db.update("DELETE FROM subscription WHERE user_id=?", u);
        for (long c : input.categoryIds()) db.update("INSERT INTO subscription VALUES (?,?)", u, c);
        event("USER", u);
        return Map.of("status", "rebuild queued");
    }

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(Principal p, @Valid @RequestBody ArticleInput a) {
        long id = db.queryForObject("INSERT INTO article(author_id,title,body) VALUES (?,?,?) RETURNING id", Long.class, user(p), a.title(), a.body());
        classify(id, a.categoryIds());
        event("ARTICLE", id);
        return article(id);
    }

    @PutMapping("/articles/{id}")
    @Transactional
    public Map<String, Object> update(Principal p, @PathVariable long id, @Valid @RequestBody ArticleInput a) {
        int n = db.update("UPDATE article SET title=?,body=?,updated_at=now() WHERE id=? AND author_id=?", a.title(), a.body(), id, user(p));
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found or not owned");
        db.update("DELETE FROM article_category WHERE article_id=?", id);
        classify(id, a.categoryIds());
        event("ARTICLE", id);
        return article(id);
    }

    void classify(long id, Set<Long> ids) {
        for (long c : ids) db.update("INSERT INTO article_category VALUES (?,?)", id, c);
    }

    void event(String kind, long id) {
        db.update("INSERT INTO outbox(kind,aggregate_id) VALUES (?,?)", kind, id);
    }

    @GetMapping("/articles/{id}")
    public Map<String, Object> article(@PathVariable long id) {
        var rows = db.queryForList("SELECT a.*,u.display_name AS author FROM article a JOIN app_user u ON u.id=a.author_id WHERE a.id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found");
        return withCategories(rows).getFirst();
    }

    @GetMapping("/articles")
    public List<Map<String, Object>> articles(@RequestParam(defaultValue = "9007199254740991") long before, @RequestParam(defaultValue = "20") int limit) {
        validatePage(before, limit);
        return withCategories(db.queryForList("SELECT a.*,u.display_name AS author FROM article a JOIN app_user u ON u.id=a.author_id WHERE a.id<? ORDER BY a.id DESC LIMIT ?", before, limit));
    }

    // Batch metadata for the whole page; never query categories once per article.
    List<Map<String, Object>> withCategories(List<Map<String, Object>> articles) {
        if (articles.isEmpty()) return articles;
        var ids = articles.stream().map(a -> a.get("id")).toArray();
        var rows = db.queryForList("SELECT ac.article_id,c.id,c.name FROM article_category ac JOIN category c ON c.id=ac.category_id WHERE ac.article_id IN ("
                + String.join(",", Collections.nCopies(ids.length, "?")) + ") ORDER BY c.id", ids);
        var grouped = new HashMap<Long, List<Map<String, Object>>>();
        for (var row : rows) {
            long id = ((Number) row.get("article_id")).longValue();
            grouped.computeIfAbsent(id, ignored -> new ArrayList<>())
                    .add(Map.of("id", row.get("id"), "name", row.get("name")));
        }
        for (var article : articles) {
            var categories = grouped.getOrDefault(((Number) article.get("id")).longValue(), List.of());
            article.put("categories", categories);
            article.put("categoryIds", categories.stream().map(c -> c.get("id")).toList());
        }
        return articles;
    }

    static void validatePage(long before, int limit) {
        if (before < 1 || before > 9007199254740991L || limit < 1 || limit > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "before must be a positive safe integer; limit must be 1..100");
    }

    @GetMapping("/feed")
    public Map<String, Object> feed(Principal p, @RequestParam(defaultValue = "9007199254740991") long before, @RequestParam(defaultValue = "20") int limit) {
        validatePage(before, limit);
        var ids = feeds.page(user(p), before, limit + 1);
        boolean more = ids.size() > limit;
        var page = ids.stream().limit(limit).toList();
        List<Map<String, Object>> items = page.isEmpty() ? List.of() : db.queryForList("SELECT a.*,u.display_name AS author FROM article a JOIN app_user u ON u.id=a.author_id WHERE a.id IN (" + String.join(",", Collections.nCopies(page.size(), "?")) + ") ORDER BY a.id DESC", page.toArray());
        var response = new LinkedHashMap<String, Object>();
        response.put("items", withCategories(items));
        response.put("nextCursor", more ? page.getLast() : null);
        return response;
    }
}
