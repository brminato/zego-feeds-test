"""Optional disruptive test: temporarily stops this Compose project's RabbitMQ.
Run from the repository root against its local demo services only.
"""
import subprocess,time
from integration_test import call,eventually,DB
import psycopg
try:
 subprocess.run(['docker','compose','stop','rabbitmq'],check=True)
 article=call('/api/articles','POST',{'title':'Broker outage test','body':'Publication succeeds while broker is unavailable.','categoryIds':[1]},'user001@example.com:FeedDemo123!')['id']
 with psycopg.connect(DB) as db:
  assert db.execute("SELECT published_at FROM outbox WHERE kind='ARTICLE' AND aggregate_id=%s",(article,)).fetchone()[0] is None
 print('Article committed with pending outbox while RabbitMQ was stopped.')
finally:
 subprocess.run(['docker','compose','up','-d','--wait','rabbitmq'],check=True)
eventually(lambda:any(a['id']==article for a in call('/api/feed',auth='user001@example.com:FeedDemo123!')['items']),timeout=90)
print('Broker recovered; relay drained pending event and feed contains the article.')
