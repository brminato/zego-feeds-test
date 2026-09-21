"""Real-service integration tests. Run after seeding; creates an isolated test user/article."""
import base64,json,os,time,unittest,uuid,urllib.request,urllib.error
import psycopg,redis
API=os.getenv('API_URL','http://localhost:8080')
DB=os.getenv('DATABASE_URL','postgresql://feed:feed-dev@localhost:5432/feed')
RABBIT=os.getenv('RABBIT_MANAGEMENT_URL','http://localhost:15672')

def call(path,method='GET',body=None,auth=None,base=API):
 headers={'Content-Type':'application/json'}
 if auth: headers['Authorization']='Basic '+base64.b64encode(auth.encode()).decode()
 req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 with urllib.request.urlopen(req,timeout=10) as r:return json.load(r)

def eventually(fn,timeout=60):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  if fn():return
  time.sleep(.3)
 raise AssertionError('Condition did not converge within deadline')

class PipelineTest(unittest.TestCase):
 def test_complete_pipeline(self):
  auth='user001@example.com:FeedDemo123!'
  first=call('/api/feed?limit=100',auth=auth)
  self.assertEqual(len(first['items']),100)
  second=call('/api/feed?limit=100&before='+str(first['nextCursor']),auth=auth)
  self.assertFalse(set(x['id'] for x in first['items']) & set(x['id'] for x in second['items']))
  with self.assertRaises(urllib.error.HTTPError) as e:call('/api/feed?limit=101',auth=auth)
  self.assertEqual(e.exception.code,400)
  email='integration-'+uuid.uuid4().hex+'@example.com'; testauth=email+':TestPassword123!'
  user=call('/api/register','POST',{'email':email,'name':'Integration writer','password':'TestPassword123!','role':'WRITER'})['id']
  call('/api/subscriptions','PUT',{'categoryIds':[1]},testauth)
  article=call('/api/articles','POST',{'title':'Integration article','body':'Asynchronous pipeline test','categoryIds':[1,2]},testauth)['id']
  eventually(lambda:any(x['id']==article for x in call('/api/feed',auth=testauth)['items']))
  with psycopg.connect(DB) as db:
   event=db.execute("SELECT id FROM outbox WHERE kind='ARTICLE' AND aggregate_id=%s",(article,)).fetchone()[0]
   before=db.execute('SELECT count(*) FROM feed_entry WHERE user_id=%s AND article_id=%s',(user,article)).fetchone()[0]
  self.assertEqual(before,1)
  result=call('/api/exchanges/%2F/feed.events/publish','POST',{'properties':{'delivery_mode':2,'content_type':'text/plain'},'routing_key':'work','payload':str(event),'payload_encoding':'string'},'feed:feed-dev',RABBIT)
  self.assertTrue(result['routed']);time.sleep(1)
  with psycopg.connect(DB) as db:
   self.assertEqual(db.execute('SELECT count(*) FROM feed_entry WHERE user_id=%s AND article_id=%s',(user,article)).fetchone()[0],1)
   self.assertEqual(db.execute('SELECT count(*) FROM processed_event WHERE event_id=%s',(event,)).fetchone()[0],1)
   v=db.execute('SELECT feed_version FROM app_user WHERE id=%s',(user,)).fetchone()[0]
  cache=redis.Redis.from_url(os.getenv('REDIS_URL','redis://localhost:6379/0'))
  cache.delete(f'feed:{{{user}}}:{v}')
  self.assertEqual(call('/api/feed',auth=testauth)['items'][0]['id'],article)
  self.assertGreater(cache.zcard(f'feed:{{{user}}}:{v}'),1)
  # Changing article categories removes it from formerly matching feeds.
  call('/api/articles/'+str(article),'PUT',{'title':'Updated integration article','body':'Now category 2 only','categoryIds':[2]},testauth)
  eventually(lambda:all(x['id']!=article for x in call('/api/feed',auth=testauth)['items']))
  call('/api/subscriptions','PUT',{'categoryIds':[]},testauth)
  eventually(lambda:call('/api/feed',auth=testauth)['items']==[])
  call('/api/subscriptions','PUT',{'categoryIds':[2]},testauth)
  eventually(lambda:any(x['id']==article for x in call('/api/feed',auth=testauth)['items']))
  # Invalid input must not commit article or outbox rows.
  with psycopg.connect(DB) as db: count=db.execute('SELECT count(*) FROM article').fetchone()[0]
  with self.assertRaises(urllib.error.HTTPError) as e:call('/api/articles','POST',{'title':'','body':'x','categoryIds':[99]},testauth)
  self.assertEqual(e.exception.code,400)
  with psycopg.connect(DB) as db:self.assertEqual(db.execute('SELECT count(*) FROM article').fetchone()[0],count)
  # Require a new DLQ message, even when the suite has run before.
  initial_dead=call('/api/queues/%2F/feed.dlq',auth='feed:feed-dev',base=RABBIT).get('messages_ready',0)
  call('/api/exchanges/%2F/feed.events/publish','POST',{'properties':{'content_type':'text/plain'},'routing_key':'work','payload':'invalid-event','payload_encoding':'string'},'feed:feed-dev',RABBIT)
  eventually(lambda:call('/api/queues/%2F/feed.dlq',auth='feed:feed-dev',base=RABBIT).get('messages_ready',0)>initial_dead)
  print('Verified publish/outbox/RabbitMQ/fan-out, auth, validation, cursor, deduplication, cache recovery, category edits, subscription rebuild and DLQ.')

if __name__=='__main__':unittest.main(verbosity=2)
