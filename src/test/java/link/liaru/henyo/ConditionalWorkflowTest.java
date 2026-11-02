package link.liaru.henyo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ConditionalWorkflowTest {
    static final ConditionalWorkflow.Target TARGET=new ConditionalWorkflow.Target("example.app",7,0);
    static final ConditionalWorkflow.Selector SEARCH=selector("Search"), RESULT=selector("Example Notes"), DONE=selector("Opened Example Notes");
    static ConditionalWorkflow.Selector selector(String text){return new ConditionalWorkflow.Selector(text,"text");}
    static ConditionalWorkflow.Step click(ConditionalWorkflow.Selector selector){return ConditionalWorkflow.Step.action(new ConditionalWorkflow.Action("ui.click","{}",selector));}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static ConditionalWorkflow.Plan plan(List<ConditionalWorkflow.Step> steps,ConditionalWorkflow.Selector verify,int budget){return new ConditionalWorkflow.Plan(TARGET,steps,verify,budget);}
    static class Fake implements ConditionalWorkflow.Adapter {
        long clock;int phase,dispatches,failAt,searchProbes;long unknownUntil=-1;
        boolean unknown,throwAction,stopAfterFirst,stopInWait,targetChanged,changeCondition;
        final ControlOwnership control=new ControlOwnership(false);final long ticket=control.ticket();
        Fake(){control.check(ticket,"synthetic-client");}
        public void checkCurrent(){if(!control.isCurrent(ticket,"synthetic-client"))throw new OperationFailure("control_stopped",409,"not_applied");}
        public long nowMs(){return clock;}
        public void pause(long millis){clock+=millis;if(stopInWait){control.stop();control.resume();}}
        public ConditionalWorkflow.Presence probe(ConditionalWorkflow.Target target,ConditionalWorkflow.Selector selector){
            clock++;
            if(targetChanged)throw new OperationFailure("workflow_target_changed",409,"not_applied");
            if(unknown||clock<unknownUntil)return ConditionalWorkflow.Presence.UNKNOWN;
            if(phase==1&&clock>=350)phase=2;
            if(selector==SEARCH&&changeCondition&&++searchProbes>1)return ConditionalWorkflow.Presence.ABSENT;
            boolean present=selector.text.equals("Search")&&phase==0||selector.text.equals("Example Notes")&&phase==2
                    ||selector.text.equals("Opened Example Notes")&&phase==3;
            return present?ConditionalWorkflow.Presence.PRESENT:ConditionalWorkflow.Presence.ABSENT;
        }
        public ConditionalWorkflow.ActionResult act(ConditionalWorkflow.Target target,ConditionalWorkflow.Action action){
            dispatches++;
            if(throwAction)throw new IllegalStateException("synthetic uncertain native submission");
            if(dispatches==failAt)return new ConditionalWorkflow.ActionResult(false,false,"action_rejected");
            phase=action.selector==SEARCH?1:3;
            if(stopAfterFirst){control.stop();control.resume();}
            return new ConditionalWorkflow.ActionResult(true,true,"");
        }
    }
    public static void main(String[] args){
        List<ConditionalWorkflow.Step> navigation=Arrays.asList(
                ConditionalWorkflow.Step.branch(SEARCH,Collections.singletonList(click(SEARCH)),Collections.emptyList()),
                ConditionalWorkflow.Step.waitFor(RESULT,1000),click(RESULT),ConditionalWorkflow.Step.waitFor(DONE,1000));
        Fake fake=new Fake();ConditionalWorkflow.Result result=new ConditionalWorkflow(plan(navigation,DONE,5000),fake).run();
        check(result.completed()&&result.actionsAccepted==2&&fake.dispatches==2,"synthetic navigation completes exactly once per action");
        check(result.toJson().contains("\"accepted\"")&&!result.toJson().contains("Example Notes"),"report retains acceptance without echoing UI content");
        fake=new Fake();fake.unknown=true;
        result=new ConditionalWorkflow(plan(Collections.singletonList(ConditionalWorkflow.Step.branch(SEARCH,Collections.emptyList(),Collections.singletonList(click(SEARCH)))),DONE,5000),fake).run();
        check(result.state.equals("needs_agent")&&fake.dispatches==0,"unknown condition cannot choose else");
        fake=new Fake();fake.changeCondition=true;
        result=new ConditionalWorkflow(plan(Collections.singletonList(ConditionalWorkflow.Step.branch(SEARCH,Collections.singletonList(click(SEARCH)),Collections.emptyList())),DONE,5000),fake).run();
        check(result.state.equals("needs_agent")&&fake.dispatches==0,"branch condition is rechecked before its first mutation");
        fake=new Fake();fake.unknownUntil=100;
        result=new ConditionalWorkflow(plan(Collections.singletonList(ConditionalWorkflow.Step.waitFor(SEARCH,300)),SEARCH,5000),fake).run();
        check(result.completed()&&fake.clock>=100&&fake.dispatches==0,"bounded read-only wait can recover from uncertainty without treating it as absence");
        fake=new Fake();fake.unknown=true;
        result=new ConditionalWorkflow(plan(Collections.singletonList(ConditionalWorkflow.Step.waitFor(SEARCH,100)),SEARCH,5000),fake).run();
        check(result.state.equals("needs_agent")&&fake.clock>=100&&fake.dispatches==0,"unknown wait expires without dispatch");
        fake=new Fake();
        result=new ConditionalWorkflow(plan(Collections.singletonList(ConditionalWorkflow.Step.waitFor(DONE,2000)),DONE,80),fake).run();
        check(result.state.equals("deadline_exceeded")&&fake.dispatches==0,"overall budget expires before pending action");
        fake=new Fake();fake.stopInWait=true;
        result=new ConditionalWorkflow(plan(Arrays.asList(ConditionalWorkflow.Step.waitFor(DONE,2000),click(SEARCH)),DONE,5000),fake).run();
        check(result.state.equals("stopped")&&fake.dispatches==0,"stop/resume during wait does not renew the original ticket");
        fake=new Fake();fake.stopAfterFirst=true;
        result=new ConditionalWorkflow(plan(navigation,DONE,5000),fake).run();
        check(result.state.equals("stopped")&&result.actionsAccepted==1&&fake.dispatches==1&&result.outcome().equals("unknown"),"stop between steps preserves accepted effects and prevents the next dispatch");
        fake=new Fake();fake.failAt=2;
        result=new ConditionalWorkflow(plan(navigation,DONE,5000),fake).run();
        check(result.state.equals("failed")&&result.actionsAccepted==1&&fake.dispatches==2&&result.outcome().equals("unknown"),"partial failure never retries or erases accepted work");
        fake=new Fake();fake.failAt=1;
        result=new ConditionalWorkflow(plan(navigation,DONE,5000),fake).run();
        check(result.state.equals("failed")&&fake.dispatches==1&&result.outcome().equals("not_applied"),"first rejected action has no hidden retry");
        fake=new Fake();fake.throwAction=true;
        result=new ConditionalWorkflow(plan(navigation,DONE,5000),fake).run();
        check(result.state.equals("failed")&&fake.dispatches==1&&result.outcome().equals("unknown"),"unexpected submission failure cannot establish absence of effects");
        fake=new Fake();fake.targetChanged=true;
        result=new ConditionalWorkflow(plan(navigation,DONE,5000),fake).run();
        check(result.state.equals("needs_agent")&&fake.dispatches==0,"changed target cannot redirect an action");
        fake=new Fake();
        result=new ConditionalWorkflow(plan(Collections.singletonList(click(SEARCH)),DONE,5000),fake).run();
        check(result.state.equals("needs_agent")&&result.actionsAccepted==1,"dispatch does not imply final postcondition");
        try{new ConditionalWorkflow.Action("termux.exec","{}",null);throw new AssertionError("unrestricted action accepted");}catch(IllegalArgumentException expected){}
        try{new ConditionalWorkflow.Selector(" ","text");throw new AssertionError("empty selector accepted");}catch(IllegalArgumentException expected){}
        try{plan(Collections.nCopies(16,click(SEARCH)),DONE,5000);throw new AssertionError("oversized plan accepted");}catch(IllegalArgumentException expected){}
        try{plan(navigation,DONE,5001);throw new AssertionError("unbounded lifetime accepted");}catch(IllegalArgumentException expected){}
        ConditionalWorkflow.Step nested=ConditionalWorkflow.Step.require(SEARCH);
        for(int i=0;i<3;i++)nested=ConditionalWorkflow.Step.branch(SEARCH,Collections.singletonList(nested),Collections.emptyList());
        try{plan(Collections.singletonList(nested),DONE,5000);throw new AssertionError("deep branches accepted");}catch(IllegalArgumentException expected){}
        semanticTests();probeTests();System.out.println("ConditionalWorkflowTest passed");
    }
    static void semanticTests() {
        class SemanticFake extends Fake {
            boolean enabled=true,accepted=true,changed;long inferenceMs=150;
            public ConditionalWorkflow.SemanticResult semantic(ConditionalWorkflow.Target target,ConditionalWorkflow.SemanticCondition condition) {
                clock+=inferenceMs;
                return new ConditionalWorkflow.SemanticResult(enabled&&accepted,"selected row",!enabled?"assistance_disabled":"workflow_semantic_uncertain");
            }
            public boolean semanticCurrent(ConditionalWorkflow.Target target,ConditionalWorkflow.SemanticCondition condition,String fingerprint) {
                return enabled&&!changed&&"selected row".equals(fingerprint);
            }
        }
        ConditionalWorkflow.Step semantic=ConditionalWorkflow.Step.semantic(new ConditionalWorkflow.SemanticCondition(RESULT,"Does this result name the requested app?"));
        List<ConditionalWorkflow.Step> steps=Arrays.asList(click(SEARCH),ConditionalWorkflow.Step.waitFor(RESULT,1000),semantic,click(RESULT));
        SemanticFake fake=new SemanticFake();ConditionalWorkflow.Result result=new ConditionalWorkflow(plan(steps,DONE,5000),fake).run();
        check(result.completed()&&fake.dispatches==2,"semantic match permits the next explicitly planned action with final verification");
        fake=new SemanticFake();fake.enabled=false;result=new ConditionalWorkflow(plan(steps,DONE,5000),fake).run();
        check(result.state.equals("needs_agent")&&result.code.equals("assistance_disabled")&&result.actionsAccepted==1&&fake.dispatches==1,"disabled assistance retains partial effects and never clicks the result");
        fake=new SemanticFake();fake.accepted=false;result=new ConditionalWorkflow(plan(steps,DONE,5000),fake).run();
        check(result.code.equals("workflow_semantic_uncertain")&&fake.dispatches==1,"uncertain model result is not actionable");
        fake=new SemanticFake();fake.changed=true;result=new ConditionalWorkflow(plan(steps,DONE,5000),fake).run();
        check(result.code.equals("workflow_semantic_changed")&&fake.dispatches==1,"changed semantic context invalidates the answer before mutation");
        fake=new SemanticFake();fake.inferenceMs=5000;result=new ConditionalWorkflow(plan(steps,DONE,5000),fake).run();
        check(result.state.equals("deadline_exceeded")&&fake.dispatches==1,"late semantic result cannot bypass the workflow deadline");
    }
    static class Node{boolean match,fail;List<Node>children=new ArrayList<>();int reported=-1;}
    static class Access implements WorkflowPredicateProbe.Access<Node>{
        int acquired,recycled;
        public boolean matches(Node node){if(node.fail)throw new IllegalStateException();return node.match;}
        public int childCount(Node node){return node.reported<0?node.children.size():node.reported;}
        public Node child(Node node,int index){acquired++;return index<node.children.size()?node.children.get(index):null;}
        public void recycle(Node node){recycled++;}
    }
    static void probeTests(){
        Node root=new Node(),one=new Node();one.match=true;root.children.add(one);Access access=new Access();
        check(WorkflowPredicateProbe.probe(root,access)==ConditionalWorkflow.Presence.PRESENT&&access.recycled==1,"one complete visible match is present");
        Node two=new Node();two.match=true;root.children.add(two);
        check(WorkflowPredicateProbe.probe(root,new Access())==ConditionalWorkflow.Presence.UNKNOWN,"duplicate targets are uncertain");
        root.children.clear();check(WorkflowPredicateProbe.probe(root,new Access())==ConditionalWorkflow.Presence.ABSENT,"only complete traversal proves absence");
        root.children.add(null);check(WorkflowPredicateProbe.probe(root,new Access())==ConditionalWorkflow.Presence.UNKNOWN,"missing children cannot prove absence");
        root.children.clear();one.fail=true;root.children.add(one);access=new Access();
        check(WorkflowPredicateProbe.probe(root,access)==ConditionalWorkflow.Presence.UNKNOWN&&access.recycled==1,"unavailable predicate releases child handles");
        root.children.clear();root.reported=Integer.MAX_VALUE;access=new Access();
        check(WorkflowPredicateProbe.probe(root,access)==ConditionalWorkflow.Presence.UNKNOWN&&access.acquired<1200,"null child attempts consume the finite budget");
        check(WorkflowPredicateProbe.probe(null,new Access())==ConditionalWorkflow.Presence.UNKNOWN,"missing root is unknown");
    }
}
