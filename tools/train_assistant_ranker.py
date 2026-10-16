"""Train the small rule relevance classifier; held-out queries never fit weights or threshold."""
import argparse, hashlib, json
from pathlib import Path
import numpy as np


def rows(cases):
    features, labels, weights = [], [], []
    for case in cases:
        expected=set(case['expected']); count=len(case['candidates']); positives=sum(c['number'] in expected for c in case['candidates'])
        for candidate in case['candidates']:
            positive=candidate['number'] in expected
            features.append(candidate['features']);labels.append(float(positive))
            weights.append((.15 if case['split']=='training' else 1)*(1/max(1,positives) if positive else 1/max(1,count-positives)))
    return np.array(features),np.array(labels),np.array(weights)


def sigmoid(z):
    return 1/(1+np.exp(-np.clip(z,-40,40)))


def train(cases, penalty):
    x,y,weight=rows(cases);coefs=np.zeros(x.shape[1]);ridge=np.eye(x.shape[1])*penalty;ridge[0,0]=0
    def loss(w):
        z=x@w
        return np.sum(weight*(np.logaddexp(0,z)-y*z))+.5*w@ridge@w
    for _ in range(100):
        p=sigmoid(x@coefs);grad=x.T@(weight*(p-y))+ridge@coefs
        hess=x.T@((weight*p*(1-p))[:,None]*x)+ridge+np.eye(x.shape[1])*1e-8
        step=np.linalg.solve(hess,grad);amount=1;before=loss(coefs)
        while amount>1e-7 and loss(coefs-step*amount)>before:
            amount*=.5
        coefs-=step*amount
        if np.linalg.norm(step*amount)<1e-6:break
    return coefs


def score(cases, weights):
    return [(c,sorted([(v['number'],float(sigmoid(np.dot(v['features'],weights)))) for v in c['candidates']],key=lambda pair:pair[1],reverse=True)) for c in cases]


def metrics(scored, threshold):
    positive=[r for r in scored if r[0]['expected']];negative=[r for r in scored if not r[0]['expected']]
    top1=sum(bool(r) and r[0][1]>=threshold and r[0][0] in c['expected'] for c,r in positive)/max(1,len(positive))
    top3=sum(any(n in c['expected'] and s>=threshold for n,s in r[:3]) for c,r in positive)/max(1,len(positive))
    reject=sum(not r or r[0][1]<threshold for c,r in negative)/max(1,len(negative))
    return dict(top1=top1,top3=top3,reject_unanswerable=reject,positive_cases=len(positive),negative_cases=len(negative))


def main():
    p=argparse.ArgumentParser();p.add_argument('--input',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--report',type=Path,required=True);args=p.parse_args()
    source=args.input.read_bytes();data=json.loads(source);fitting=[c for c in data['cases'] if c['split']!='holdout'];holdout=[c for c in data['cases'] if c['split']=='holdout'];servers=sorted({c['server'] for c in fitting});best=None
    for penalty in [.001,.01,.1,1,10]:
        oof=[]
        for server in servers:
            weights=train([c for c in fitting if c['server']!=server],penalty)
            oof.extend(score([c for c in fitting if c['server']==server and c['split']=='validation'],weights))
        for threshold in np.arange(.15,.96,.01):
            m=metrics(oof,float(threshold));objective=(m['top1']+m['reject_unanswerable'])/2
            selection=(objective,m['reject_unanswerable'],m['top3'],-penalty)
            if best is None or selection>best[0]:best=(selection,penalty,float(threshold),m)
    _,penalty,threshold,cv=best;weights=train(fitting,penalty);scored=score(holdout,weights);result=metrics(scored,threshold)
    spec=dict(features=data['features'],weights=weights.tolist(),threshold=round(threshold,6),encoder='sergeyzh/rubert-mini-frida',encoderSha256='6839e61573be6adee64a252354a47b42a0aaabf6a78132885007f23291b92e62',training=dict(method='query-balanced logistic regression, L2, leave-one-server-out calibration',penalty=penalty,queries=len(fitting),featuresSha256=hashlib.sha256(source).hexdigest()))
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(spec,ensure_ascii=False,indent=2)+'\n','utf-8')
    report=dict(cross_validation=cv,holdout=result,threshold=threshold,failures=[dict(query=c['query'],server=c['server'],expected=c['expected'],ranked=r[:3]) for c,r in scored if (c['expected'] and (not r or r[0][1]<threshold or r[0][0] not in c['expected'])) or (not c['expected'] and r and r[0][1]>=threshold)])
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n','utf-8');print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
