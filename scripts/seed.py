"""Idempotent fixture upsert. Requires migrated schema and running worker."""
import os,time
import psycopg,redis,bcrypt
DB=os.getenv('DATABASE_URL','postgresql://feed:feed-dev@localhost:5432/feed')
r=redis.Redis.from_url(os.getenv('REDIS_URL','redis://localhost:6379/0'),decode_responses=True)
# Fixed salt ONLY for reproducible demo fixtures; registration uses random salts.
password=bcrypt.hashpw(b'FeedDemo123!',b'$2b$12$abcdefghijklmnopqrstuu').decode()
with psycopg.connect(DB) as db:
 with db.cursor() as c:
  c.execute("SELECT to_regclass('public.outbox')")
  if c.fetchone()[0] is None: raise SystemExit('Start backend once to apply Flyway migrations before seeding.')
  # Reserved fixture IDs: 1..100 users, 1..1000 articles. Never silently overwrite real data.
  c.execute("SELECT count(*) FROM app_user WHERE id<=100 AND email <> 'user'||lpad(id::text,3,'0')||'@example.com'")
  if c.fetchone()[0]: raise SystemExit('Reserved user IDs occupied. Use a fresh demo database (see README reset).')
  c.execute("SELECT count(*) FROM article WHERE id<=1000 AND title <> 'Engineering note '||lpad(id::text,4,'0')")
  if c.fetchone()[0]: raise SystemExit('Reserved article IDs occupied. Reset the demo database first.')
  c.execute("""INSERT INTO app_user(id,email,display_name,password_hash,role)
   SELECT i,'user'||lpad(i::text,3,'0')||'@example.com','Writer '||lpad(i::text,3,'0'),%s,'WRITER' FROM generate_series(1,100) i
   ON CONFLICT(id) DO UPDATE SET password_hash=excluded.password_hash""",(password,))
  c.execute("INSERT INTO subscription SELECT u.id,c.id FROM app_user u CROSS JOIN category c WHERE u.id<=100 ON CONFLICT DO NOTHING")
  c.execute("""INSERT INTO article(id,author_id,title,body,created_at,updated_at)
   SELECT i,1+(i-1)%100,'Engineering note '||lpad(i::text,4,'0'),
    'A practical field note on building reliable systems. Explore the trade-offs, measure the outcome, and keep the design understandable.',
    timestamptz '2026-01-01 00:00:00+00' + i * interval '1 minute',
    timestamptz '2026-01-01 00:00:00+00' + i * interval '1 minute'
   FROM generate_series(1,1000) i ON CONFLICT(id) DO NOTHING""")
  c.execute("INSERT INTO article_category SELECT i,1+(i-1)%10 FROM generate_series(1,1000) i ON CONFLICT DO NOTHING")
  for table in ('app_user','article'):
   c.execute(f"SELECT setval(pg_get_serial_sequence('{table}','id'),(SELECT max(id) FROM {table}))")
  c.execute("INSERT INTO outbox(kind,aggregate_id) SELECT 'USER',i FROM generate_series(1,100) i RETURNING id")
  events=[row[0] for row in c.fetchall()]
 print('Fixtures committed; waiting for 100 materialized feeds through outbox → RabbitMQ → worker…')
 deadline=time.monotonic()+180
 while time.monotonic()<deadline:
  with db.cursor() as c:
   c.execute('SELECT count(*) FROM processed_event WHERE event_id=ANY(%s)',(events,))
   done=c.fetchone()[0]
   c.execute('SELECT id,feed_version FROM app_user WHERE id<=100 ORDER BY id')
   versions=c.fetchall()
  db.commit()
  if done==100 and all(r.zcard(f'feed:{{{u}}}:{v}')-1==1000 for u,v in versions):
   print('Verified seed fixtures: 100 reserved users, 10 categories, 1000 reserved articles; each seeded user has 1000 Redis entries (100,000 total).')
   break
  time.sleep(1)
 else: raise SystemExit('Timed out. Check worker logs/DLQ. Existing extra articles may change fixture feed contents; reset for exact fixtures.')
