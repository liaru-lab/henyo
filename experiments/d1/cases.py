"""Synthetic English/Japanese decision cases; no device observations."""
import json
cases=[]
def make(lang,kind,index,nodes,**changes):
 state={'goal':{'instruction':'Open Example Notes' if lang=='en' else 'Example Notes を開く','target':'Example Notes'},'expectedPackage':'example.search','package':'example.search','fresh':True,'coverage':'complete','nodes':nodes}
 state.update(changes);cases.append({'id':lang+'-'+kind+'-'+str(index),'language':lang,'expected':kind,'state':state})
def node(text,role='text',clickable=False,enabled=True):return {'text':text,'role':role,'clickable':clickable,'enabled':enabled}
for lang in ('en','ja'):
 loading=['Loading results','Searching','Fetching matches','Please wait'] if lang=='en' else ['読み込み中','検索中','検索結果を取得中','しばらくお待ちください']
 for i,text in enumerate(loading):make(lang,'wait',i,[node(text,'progressbar')])
 for i in range(4):make(lang,'open_result',i,[node('Search results' if lang=='en' else '検索結果'),node('Example Notes','button',True),node('Example Calendar' if i%2 else 'Example Photos','button',True)])
 for i in range(4):make(lang,'finish',i,[node('Opened Example Notes' if lang=='en' else 'Example Notes を開きました','status'),node(['Ready','All done','Navigation complete','Finished'][i] if lang=='en' else ['準備完了','完了','移動しました','終了'][i])])
 make(lang,'needs_agent',0,[],coverage='unavailable')
 make(lang,'needs_agent',1,[node('Example Notes','button',True)],fresh=False)
 make(lang,'needs_agent',2,[node('Example Notes','button',True),node('Example Notes','button',True)])
 make(lang,'needs_agent',3,[node('Delete all notes?' if lang=='en' else 'すべてのノートを削除しますか？'),node('Confirm' if lang=='en' else '確認','button',True)])
 make(lang,'needs_agent',4,[node('Sign in' if lang=='en' else 'ログイン'),node('Password' if lang=='en' else 'パスワード','input',True)])
 make(lang,'needs_agent',5,[node('Example Notes','button',True,False)])
 make(lang,'needs_agent',6,[node('Example Notes','button',True)],package='example.other')
 make(lang,'needs_agent',7,[node('No matches' if lang=='en' else '該当する項目がありません')])
for lang in ('en', 'ja'):
 for i in range(4):
  make(lang,'search',i,[node('Search' if lang=='en' else '検索','button',True)])

if __name__ == '__main__':
 print(json.dumps(cases,ensure_ascii=False,indent=2))
