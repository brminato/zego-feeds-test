import { useEffect, useState, type FormEvent } from "react";
import { createRoot } from "react-dom/client";
import { authorization, request } from "./api";
import "./style.css";
type Category = { id: number; name: string };
type Article = {
  id: number;
  title: string;
  body: string;
  author: string;
  author_id: number;
  created_at: string;
  categories: Category[];
};
type Page = { items: Article[]; nextCursor: number | null };
type Role = "READER" | "WRITER";
type Me = { id: number; display_name: string; role: Role };
function App() {
  const [role, setRole] = useState<Role>("READER");
  const [auth, setAuth] = useState(""),
    [email, setEmail] = useState("user001@example.com"),
    [password, setPassword] = useState("FeedDemo123!");
  const [register, setRegister] = useState(false),
    [name, setName] = useState(""),
    [me, setMe] = useState<Me | null>(null);
  const [categories, setCategories] = useState<Category[]>([]),
    [selected, setSelected] = useState<number[]>([]),
    [tags, setTags] = useState<number[]>([1]);
  const [page, setPage] = useState<Page>({ items: [], nextCursor: null }),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false);
  const [title, setTitle] = useState(""),
    [body, setBody] = useState(""),
    [editing, setEditing] = useState<number | null>(null);
  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Something went wrong");
    } finally {
      setBusy(false);
    }
  }
  async function login(e: FormEvent) {
    e.preventDefault();
    await run(async () => {
      if (register)
        await request("", "/register", "POST", { email, name, password, role });
      const a = authorization(email, password);
      const user = await request<Me>(a, "/me");
      setMe(user);
      setAuth(a);
    });
  }
  async function refresh(a = auth) {
    setPage(await request<Page>(a, "/feed"));
  }
  useEffect(() => {
    if (!auth) return;
    let active = true;
    run(async () => {
      const [c, s, p] = await Promise.all([
        request<Category[]>(auth, "/categories"),
        request<number[]>(auth, "/subscriptions"),
        request<Page>(auth, "/feed"),
      ]);
      if (active) {
        setCategories(c);
        setSelected(s);
        setPage(p);
      }
    });
    return () => {
      active = false;
    };
  }, [auth]);
  function toggle(id: number, values: number[], set: (v: number[]) => void) {
    set(values.includes(id) ? values.filter((v) => v !== id) : [...values, id]);
  }
  async function publish(e: FormEvent) {
    e.preventDefault();
    await run(async () => {
      await request(
        auth,
        editing ? `/articles/${editing}` : "/articles",
        editing ? "PUT" : "POST",
        { title, body, categoryIds: tags },
      );
      setTitle("");
      setBody("");
      setEditing(null);
      setNotice(
        "Saved. Feed updates arrive asynchronously. Refresh in a few seconds.",
      );
    });
  }
  return (
    <>
      <header>
        <a className="brand" href="/">
          ◉ Zego
        </a>
        <span>Zego feeds test</span>
        {me && (
          <button
            onClick={() => {
              setAuth("");
              setMe(null);
              setPage({ items: [], nextCursor: null });
              setNotice("");
            }}
          >
            Sign out · {me.display_name} (
            {me.role === "WRITER" ? "Writer" : "Reader"})
          </button>
        )}
      </header>
      <main>
        {error && (
          <div role="alert" className="error">
            {error}
          </div>
        )}
        {notice && (
          <div role="status" className="notice">
            {notice}
          </div>
        )}
        {!auth ? (
          <section className="login">
            <p className="eyebrow">YOUR PERSONAL KNOWLEDGE STREAM</p>
            <h1>
              Less searching.
              <br />
              More discovering.
            </h1>
            <p>
              Follow your interests. Share an idea. Your next read is already
              waiting.
            </p>
            <form onSubmit={login}>
              {register && (
                <label>
                  Name
                  <input
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    required
                    maxLength={100}
                  />
                </label>
              )}
              {register && (
                <label>
                  Account type
                  <select
                    value={role}
                    onChange={(e) => setRole(e.target.value as Role)}
                  >
                    <option value="READER">
                      Reader — browse and follow categories
                    </option>
                    <option value="WRITER">
                      Writer — read and publish articles
                    </option>
                  </select>
                </label>
              )}
              <label>
                Email
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </label>
              <label>
                Password
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  minLength={10}
                  maxLength={60}
                />
              </label>
              <button className="primary" disabled={busy}>
                {register ? "Create account" : "Sign in"}
              </button>
              <button type="button" onClick={() => setRegister(!register)}>
                {register ? "Already have an account?" : "Create an account"}
              </button>
            </form>
            <small>Seed demo: user001@example.com · FeedDemo123!</small>
          </section>
        ) : (
          <div className="layout">
            <aside>
              <p className="eyebrow">MAKE IT YOURS</p>
              <h2>Your interests</h2>
              <p>Choose what shows up in your feed.</p>
              {categories.map((c) => (
                <label className="check" key={c.id}>
                  <input
                    type="checkbox"
                    checked={selected.includes(c.id)}
                    onChange={() => toggle(c.id, selected, setSelected)}
                  />
                  {c.name}
                </label>
              ))}
              <button
                className="primary"
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    await request(auth, "/subscriptions", "PUT", {
                      categoryIds: selected,
                    });
                    setNotice(
                      "Interests saved. Refresh shortly to see your rebuilt feed.",
                    );
                  })
                }
              >
                Save interests
              </button>
              <small>Changes may take a few seconds.</small>
            </aside>
            <section>
              <div className="feed-heading">
                <div>
                  <p className="eyebrow"></p>
                  <h1>Feeds</h1>
                </div>
                <button disabled={busy} onClick={() => run(() => refresh())}>
                  ↻ Refresh
                </button>
              </div>
              {me?.role === "WRITER" && (
                <details
                  className="composer"
                  open={editing !== null ? true : undefined}
                >
                  <summary>＋ Share an article</summary>
                  <form onSubmit={publish}>
                    <label>
                      Title
                      <input
                        value={title}
                        onChange={(e) => setTitle(e.target.value)}
                        required
                        maxLength={200}
                      />
                    </label>
                    <label>
                      Your article
                      <textarea
                        value={body}
                        onChange={(e) => setBody(e.target.value)}
                        required
                        maxLength={50000}
                      />
                    </label>
                    <div className="tags">
                      {categories.map((c) => (
                        <label key={c.id}>
                          <input
                            type="checkbox"
                            checked={tags.includes(c.id)}
                            onChange={() => toggle(c.id, tags, setTags)}
                          />
                          {c.name}
                        </label>
                      ))}
                    </div>
                    <button
                      className="primary"
                      disabled={busy || tags.length === 0}
                    >
                      {editing ? "Save article" : "Publish article"}
                    </button>
                    {editing && (
                      <button
                        type="button"
                        onClick={() => {
                          setEditing(null);
                          setTitle("");
                          setBody("");
                        }}
                      >
                        Cancel edit
                      </button>
                    )}
                  </form>
                </details>
              )}
              {page.items.length === 0 ? (
                <div className="empty">
                  <h2>A little quiet here.</h2>
                  <p>
                    Choose your interests and refresh your feed to discover
                    articles.
                  </p>
                </div>
              ) : (
                page.items.map((a) => (
                  <article key={a.id}>
                    <div className="meta">
                      Written by <strong>{a.author}</strong>{" "}
                      <span>
                        · {new Date(a.created_at).toLocaleDateString()}
                      </span>
                    </div>
                    <h2>{a.title}</h2>
                    <ul
                      className="article-categories"
                      aria-label="Article categories"
                    >
                      {a.categories.map((c) => (
                        <li key={c.id}>{c.name}</li>
                      ))}
                    </ul>
                    <p className="body">{a.body}</p>
                    {me?.role === "WRITER" && me.id === a.author_id && (
                      <button
                        onClick={() =>
                          run(async () => {
                            const detail = await request<
                              Article & { categoryIds: number[] }
                            >(auth, `/articles/${a.id}`);
                            setEditing(a.id);
                            setTitle(detail.title);
                            setBody(detail.body);
                            setTags(detail.categoryIds);
                          })
                        }
                      >
                        Edit article
                      </button>
                    )}
                    <footer>ARTICLE {a.id.toString().padStart(4, "0")}</footer>
                  </article>
                ))
              )}
              {page.nextCursor && (
                <button
                  disabled={busy}
                  onClick={() =>
                    run(async () => {
                      const next = await request<Page>(
                        auth,
                        `/feed?before=${page.nextCursor}`,
                      );
                      setPage((p) => ({
                        items: [...p.items, ...next.items],
                        nextCursor: next.nextCursor,
                      }));
                    })
                  }
                >
                  Load more articles
                </button>
              )}
            </section>
          </div>
        )}
      </main>
      <div className="site-footer">SIGNAL · Built around your curiosity</div>
    </>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
