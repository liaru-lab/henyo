package link.liaru.henyo;

/** A predicate is true only for one exact match in a completely inspected tree. */
final class WorkflowPredicateProbe {
    interface Access<N> {
        boolean matches(N node);
        int childCount(N node);
        N child(N node,int index);
        void recycle(N node);
    }
    private int remaining=1200,matches;private boolean incomplete;
    static <N> ConditionalWorkflow.Presence probe(N root,Access<N> access) {
        WorkflowPredicateProbe probe=new WorkflowPredicateProbe();probe.visit(root,access,0);
        if(probe.incomplete||probe.matches>1)return ConditionalWorkflow.Presence.UNKNOWN;
        return probe.matches==1?ConditionalWorkflow.Presence.PRESENT:ConditionalWorkflow.Presence.ABSENT;
    }
    private <N> void visit(N node,Access<N> access,int depth){
        if(remaining--<=0||node==null||depth>32){incomplete=true;return;}
        try {
            if(access.matches(node)&&++matches>1)return;
            int count=access.childCount(node);
            if(count<0){incomplete=true;return;}
            for(int i=0;i<count&&matches<2;i++){
                if(remaining<=0){incomplete=true;return;}
                N child=access.child(node,i);
                try{visit(child,access,depth+1);}finally{if(child!=null)access.recycle(child);}
            }
        }catch(RuntimeException unavailable){incomplete=true;}
    }
}
