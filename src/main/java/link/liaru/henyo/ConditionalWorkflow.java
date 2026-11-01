package link.liaru.henyo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Experimental bounded task fragments. Observations and native actions remain adapter-owned. */
final class ConditionalWorkflow {
    static final int MAX_STEPS = 16, MAX_DEPTH = 2, MAX_BUDGET_MS = 5000, MAX_WAIT_MS = 2000;
    enum Presence { PRESENT, ABSENT, UNKNOWN }
    enum Kind { ACTION, BRANCH, WAIT, REQUIRE }
    static final class Target {
        final String packageName; final int windowId, displayId;
        Target(String packageName, int windowId, int displayId) {
            if (packageName == null || !packageName.matches("[A-Za-z0-9_.]{1,255}") || windowId < 0 || displayId < 0)
                throw new IllegalArgumentException("invalid target");
            this.packageName=packageName; this.windowId=windowId; this.displayId=displayId;
        }
    }
    static final class Selector {
        final String text, field;
        Selector(String text, String field) {
            if (text == null || text.trim().isEmpty() || text.codePointCount(0,text.length()) > 256
                    || !("text".equals(field) || "desc".equals(field) || "viewId".equals(field)))
                throw new IllegalArgumentException("invalid selector");
            this.text=text.trim(); this.field=field;
        }
    }
    static final class Action {
        final String op, paramsJson; final Selector selector;
        Action(String op, String paramsJson, Selector selector) {
            if (!("ui.click".equals(op) || "ui.setText".equals(op) || "ui.scroll".equals(op))
                    || paramsJson == null || paramsJson.length() > 4096
                    || (!"ui.scroll".equals(op) && selector == null)) throw new IllegalArgumentException("invalid action");
            this.op=op; this.paramsJson=paramsJson; this.selector=selector;
        }
    }
    static final class Step {
        final Kind kind; final Selector selector; final Action action; final int timeoutMs;
        final List<Step> yes, no;
        private Step(Kind kind, Selector selector, Action action, int timeoutMs, List<Step> yes, List<Step> no) {
            this.kind=kind; this.selector=selector; this.action=action; this.timeoutMs=timeoutMs;
            this.yes=copy(yes); this.no=copy(no);
        }
        static Step action(Action action) { if(action==null)throw new IllegalArgumentException();return new Step(Kind.ACTION,null,action,0,null,null); }
        static Step branch(Selector selector,List<Step> yes,List<Step> no) { if(selector==null)throw new IllegalArgumentException();return new Step(Kind.BRANCH,selector,null,0,yes,no); }
        static Step waitFor(Selector selector,int timeout) {
            if(selector==null||timeout<1||timeout>MAX_WAIT_MS)throw new IllegalArgumentException();
            return new Step(Kind.WAIT,selector,null,timeout,null,null);
        }
        static Step require(Selector selector) { if(selector==null)throw new IllegalArgumentException();return new Step(Kind.REQUIRE,selector,null,0,null,null); }
    }
    static final class Plan {
        final Target target; final List<Step> steps; final Selector verify; final int budgetMs;
        Plan(Target target,List<Step> steps,Selector verify,int budgetMs) {
            if(target==null||steps==null||verify==null||budgetMs<1||budgetMs>MAX_BUDGET_MS)throw new IllegalArgumentException("invalid plan");
            this.target=target;this.steps=copy(steps);this.verify=verify;this.budgetMs=budgetMs;
            if(count(this.steps,0)+1>MAX_STEPS)throw new IllegalArgumentException("too many steps");
        }
        private int count(List<Step> steps,int depth) {
            if(depth>MAX_DEPTH)throw new IllegalArgumentException("too deep");
            int count=0;
            for(Step step:steps) {
                if(step==null)throw new IllegalArgumentException();
                count++;
                if(step.kind==Kind.BRANCH) count+=count(step.yes,depth+1)+count(step.no,depth+1);
                if(count>=MAX_STEPS)throw new IllegalArgumentException("too many steps");
            }
            return count;
        }
    }
    static final class ActionResult {
        final boolean ok, effectsPossible; final String code;
        ActionResult(boolean ok,boolean effectsPossible,String code) {this.ok=ok;this.effectsPossible=effectsPossible;this.code=!ok&&(code==null||code.isEmpty())?"operation_failed":safeCode(code);}
    }
    interface Adapter {
        void checkCurrent();
        Presence probe(Target target,Selector selector);
        ActionResult act(Target target,Action action);
        long nowMs();
        void pause(long millis) throws InterruptedException;
    }
    static final class Event {
        final int index; final String kind, state, code;
        Event(int index,String kind,String state,String code){this.index=index;this.kind=kind;this.state=state;this.code=safeCode(code);}
    }
    static final class Result {
        final String state,code; final int visited,actionsAccepted; final boolean effectsPossible;
        final List<Event> events;
        Result(String state,String code,int visited,int accepted,boolean effectsPossible,List<Event> events) {
            this.state=state;this.code=safeCode(code);this.visited=visited;actionsAccepted=accepted;
            this.effectsPossible=effectsPossible;this.events=Collections.unmodifiableList(new ArrayList<>(events));
        }
        boolean completed(){return "completed".equals(state);}
        String outcome(){return effectsPossible?"unknown":"not_applied";}
        String toJson() {
            StringBuilder out=new StringBuilder("{\"state\":\"").append(state).append("\",\"stepsVisited\":").append(visited)
                    .append(",\"actionsAccepted\":").append(actionsAccepted).append(",\"effectsPossible\":").append(effectsPossible).append(",\"steps\":[");
            for(Event event:events){if(out.charAt(out.length()-1)!='[')out.append(',');out.append("{\"index\":").append(event.index)
                    .append(",\"kind\":\"").append(event.kind).append("\",\"state\":\"").append(event.state)
                    .append("\",\"code\":\"").append(event.code).append("\"}");}
            return out.append("]}").toString();
        }
    }
    private static final class Halt extends RuntimeException {
        final String state,code;Halt(String state,String code){this.state=state;this.code=code;}
    }
    private static final class Guard {final Selector selector;final Presence expected;Guard(Selector selector,Presence expected){this.selector=selector;this.expected=expected;}}
    private final Plan plan;private final Adapter adapter;private final List<Event> events=new ArrayList<>();
    private final List<Guard> guards=new ArrayList<>();
    private int visited,accepted;private boolean effectsPossible,attemptingAction;private String currentKind="verify";private long started;
    ConditionalWorkflow(Plan plan,Adapter adapter){this.plan=plan;this.adapter=adapter;}
    Result run() {
        started=adapter.nowMs();String state="completed",code="";
        try {
            execute(plan.steps);visited++;currentKind="verify";check();
            if(probe(plan.verify)!=Presence.PRESENT)throw new Halt("needs_agent","workflow_needs_agent");
            record("verified","");
        } catch(Halt halt){state=halt.state;code=halt.code;record(state,code);}
        catch(OperationFailure failure){state=stateFor(failure.code);code=failure.code;effectsPossible|=attemptingAction||"unknown".equals(failure.outcome);record(state,code);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();state="stopped";code="workflow_interrupted";record(state,code);}
        catch(RuntimeException unexpected){state="failed";code="workflow_failed";effectsPossible|=attemptingAction;record(state,code);}
        return new Result(state,code,visited,accepted,effectsPossible,events);
    }
    private void execute(List<Step> steps) throws InterruptedException {
        for(Step step:steps) {
            visited++;currentKind=step.kind.name().toLowerCase(java.util.Locale.ROOT);check();
            switch(step.kind) {
                case BRANCH:
                    Presence condition=probe(step.selector);
                    if(condition==Presence.UNKNOWN)throw new Halt("needs_agent","workflow_needs_agent");
                    record(condition==Presence.PRESENT?"present":"absent","");
                    Guard guard=new Guard(step.selector,condition);guards.add(guard);
                    try{execute(condition==Presence.PRESENT?step.yes:step.no);}finally{guards.remove(guard);}break;
                case REQUIRE:
                    if(probe(step.selector)!=Presence.PRESENT)throw new Halt("needs_agent","workflow_needs_agent");
                    record("present","");break;
                case WAIT:
                    long began=adapter.nowMs();
                    while(true) {
                        Presence presence=probe(step.selector);
                        if(presence==Presence.PRESENT){record("present","");break;}
                        long remaining=step.timeoutMs-(adapter.nowMs()-began);
                        if(remaining<=0)throw new Halt("needs_agent","workflow_wait_timeout");
                        adapter.pause(Math.min(50L,remaining));check();
                    }
                    break;
                case ACTION:
                    for(Guard activeGuard:guards)if(probe(activeGuard.selector)!=activeGuard.expected)
                        throw new Halt("needs_agent","workflow_needs_agent");
                    if(step.action.selector!=null&&probe(step.action.selector)!=Presence.PRESENT)
                        throw new Halt("needs_agent","workflow_needs_agent");
                    check();attemptingAction=true;ActionResult result=adapter.act(plan.target,step.action);attemptingAction=false;
                    effectsPossible|=result.ok||result.effectsPossible;
                    if(!result.ok)throw new Halt(stateFor(result.code),result.code);
                    accepted++;guards.clear();record("accepted","");check();break;
            }
        }
    }
    private Presence probe(Selector selector){check();Presence result=adapter.probe(plan.target,selector);check();return result==null?Presence.UNKNOWN:result;}
    private void check(){adapter.checkCurrent();if(adapter.nowMs()-started>=plan.budgetMs)throw new Halt("deadline_exceeded","deadline_exceeded");}
    private void record(String state,String code){
        if(visited<1||(!events.isEmpty()&&events.get(events.size()-1).index==visited))return;
        events.add(new Event(visited,currentKind,state,code));
    }
    private static String stateFor(String code){
        if("deadline_exceeded".equals(code))return "deadline_exceeded";
        if("control_stopped".equals(code)||"control_owned".equals(code)||"auth_revoked".equals(code)||"auth_invalid".equals(code))return "stopped";
        if("workflow_target_changed".equals(code)||"stale_target".equals(code)||"tree_unavailable".equals(code)
                ||"sensitive_ui_permission_required".equals(code)||"control_permission_required".equals(code))return "needs_agent";
        return "failed";
    }
    private static String safeCode(String code){return code==null||code.isEmpty()?"":code.matches("[a-z][a-z0-9_]{0,63}")?code:"operation_failed";}
    private static <T> List<T> copy(List<T> values){return values==null?Collections.emptyList():Collections.unmodifiableList(new ArrayList<>(values));}
}
