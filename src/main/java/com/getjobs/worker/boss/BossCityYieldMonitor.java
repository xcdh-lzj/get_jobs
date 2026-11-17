package com.getjobs.worker.boss;

import java.util.*;

/** 任务内、账号内的统计；每个关键词独立取样，避免第一个词低产出时漏掉其他词。 */
public final class BossCityYieldMonitor {
    public enum Outcome { NEW_JOB, UNAVAILABLE, UNCERTAIN }
    private final BossCityPolicy policy;
    private final List<BossCityProgress.City> cities=new ArrayList<>();
    private final Deque<Outcome> window=new ArrayDeque<>();
    private int index=-1,processed,newJobs,uncertain,lowYieldKeywords;
    private boolean keywordEnded;
    public BossCityYieldMonitor(BossCityPolicy policy,List<String> codes,List<String> names) {
        this.policy=policy.validated();
        for(int n=0;n<codes.size();n++)cities.add(new BossCityProgress.City(codes.get(n),names.get(n),"pending",0,0,0,0,0,0,0,"等待前一城市结束"));
    }
    public boolean active(){return policy.enabled() && cities.size()>1;}
    public void startCity(int index){this.index=index;processed=newJobs=uncertain=lowYieldKeywords=0;startKeyword();update("running","正在处理本城市");}
    public void startKeyword(){window.clear();keywordEnded=false;update("running","等待当前关键词的岗位样本");}
    public void record(Outcome outcome){processed++;if(outcome==Outcome.NEW_JOB)newJobs++;if(outcome==Outcome.UNCERTAIN)uncertain++;window.addLast(outcome);while(window.size()>policy.windowSize())window.removeFirst();update("running","正在处理当前关键词，按近期岗位样本判断产出");}
    public boolean lowYield() {return active() && window.size()==policy.windowSize() && !window.contains(Outcome.UNCERTAIN) && count(Outcome.NEW_JOB)<=policy.maxNewJobs();}
    public boolean endLowYieldKeyword(){if(!lowYield() || keywordEnded)return false;keywordEnded=true;lowYieldKeywords++;update("running","最近 "+policy.windowSize()+" 个岗位仅 "+count(Outcome.NEW_JOB)+" 个新增可投，提前结束当前关键词");return true;}
    public void completeCity(int keywordCount){String reason=lowYieldKeywords>0?lowYieldKeywords+" 个关键词达到低产出阈值，其余搜索已完成":"本城市关键词已处理完毕";update(lowYieldKeywords==keywordCount?"low_yield":"completed",reason);}
    public void terminate(String state){if(index>=0 && "running".equals(cities.get(index).state()))update(state,"账号任务已结束，未继续处理本城");for(int n=index+1;n<cities.size();n++){var c=cities.get(n);cities.set(n,new BossCityProgress.City(c.code(),c.name(),"cancelled",0,0,0,0,0,0,0,"账号任务已结束，未进入此城市"));}}
    public BossCityProgress snapshot(){return new BossCityProgress(index,active(),policy.windowSize(),policy.maxNewJobs(),List.copyOf(cities));}
    private int count(Outcome type){return (int)window.stream().filter(o->o==type).count();}
    private void update(String state,String reason){if(index<0)return;var old=cities.get(index);cities.set(index,new BossCityProgress.City(old.code(),old.name(),state,processed,newJobs,uncertain,lowYieldKeywords,window.size(),count(Outcome.NEW_JOB),count(Outcome.UNCERTAIN),reason.isEmpty()?old.reason():reason));}
}
