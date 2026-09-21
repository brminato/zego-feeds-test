"""Role authorization and article metadata regression tests; needs running API/worker."""
import unittest,uuid,urllib.error
from integration_test import call,eventually

class RolesTest(unittest.TestCase):
 def test_roles_and_metadata(self):
  suffix=uuid.uuid4().hex
  reader=f'reader-{suffix}@example.com'
  writer=f'writer-{suffix}@example.com'
  password='TestPassword123!'
  r=call('/api/register','POST',{'email':reader,'name':'Test reader','password':password})
  w=call('/api/register','POST',{'email':writer,'name':'Test writer','password':password,'role':'WRITER'})
  self.assertEqual(r['role'],'READER')
  self.assertEqual(w['role'],'WRITER')
  ra=f'{reader}:{password}';wa=f'{writer}:{password}'
  self.assertEqual(call('/api/me',auth=ra)['role'],'READER')
  data={'title':'Multiple categories','body':'Role regression check','categoryIds':[1,2]}
  with self.assertRaises(urllib.error.HTTPError) as e:call('/api/articles','POST',data,ra)
  self.assertEqual(e.exception.code,403)
  article=call('/api/articles','POST',data,wa)
  expected=[{'id':1,'name':'Java'},{'id':2,'name':'Databases'}]
  self.assertEqual(article['categories'],expected)
  self.assertEqual(article['author'],'Test writer')
  listed=next(a for a in call('/api/articles',auth=ra) if a['id']==article['id'])
  self.assertEqual(listed['categories'],expected)
  self.assertEqual(listed['author_id'],w['id'])
  with self.assertRaises(urllib.error.HTTPError) as e:call(f"/api/articles/{article['id']}",'PUT',data,ra)
  self.assertEqual(e.exception.code,403)
  call('/api/subscriptions','PUT',{'categoryIds':[1]},ra)
  eventually(lambda:any(a['id']==article['id'] for a in call('/api/feed',auth=ra)['items']))
  fed=next(a for a in call('/api/feed',auth=ra)['items'] if a['id']==article['id'])
  self.assertEqual(fed['categories'],expected)
  self.assertEqual(fed['author'],'Test writer')
  data['categoryIds']=[2]
  updated=call(f"/api/articles/{article['id']}",'PUT',data,wa)
  self.assertEqual(updated['categories'],[expected[1]])
  with self.assertRaises(urllib.error.HTTPError) as e:
   call('/api/register','POST',{'email':f'invalid-{suffix}@example.com','name':'Invalid','password':password,'role':'ADMIN'})
  self.assertEqual(e.exception.code,400)

if __name__=='__main__':unittest.main(verbosity=2)
