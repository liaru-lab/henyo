#!/usr/bin/env python3
"""Exercise production text targeting with synthetic accessibility providers."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUB = r'''
package android.view.accessibility;
import java.util.*;
public final class AccessibilityNodeInfo {
    public static int live;
    public static final class Data {
        public String text; public boolean editable; public int window = 1;
        public Data parent, labelFor, labeledBy;
        public final List<Data> children = new ArrayList<>();
        public Data(String text, boolean editable) { this.text=text; this.editable=editable; }
        public Data child(Data child) { children.add(child); if(child!=null)child.parent=this; return this; }
    }
    public final Data data;
    private boolean recycled;
    public AccessibilityNodeInfo(Data data) { this.data=data; live++; }
    public static AccessibilityNodeInfo obtain(AccessibilityNodeInfo node) { return new AccessibilityNodeInfo(node.data); }
    private static AccessibilityNodeInfo wrap(Data data) { return data==null?null:new AccessibilityNodeInfo(data); }
    public void recycle() { if(recycled)throw new AssertionError("double recycle"); recycled=true; live--; }
    public int getWindowId() { return data.window; }
    public boolean isEditable() { return data.editable; }
    public AccessibilityNodeInfo getParent() { return wrap(data.parent); }
    public AccessibilityNodeInfo getLabelFor() { return wrap(data.labelFor); }
    public AccessibilityNodeInfo getLabeledBy() { return wrap(data.labeledBy); }
    public int getChildCount() { return data.children.size(); }
    public AccessibilityNodeInfo getChild(int index) { return wrap(data.children.get(index)); }
    public boolean equals(Object other) { return other instanceof AccessibilityNodeInfo && ((AccessibilityNodeInfo)other).data==data; }
    public int hashCode() { return System.identityHashCode(data); }
}
'''
TEST = r'''
package link.liaru.henyo;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.Data;
public final class TextInputTargetResolverTest {
    static void check(boolean b) { if(!b)throw new AssertionError(); }
    static Data n(String text, boolean edit) { return new Data(text,edit); }
    static Data resolve(Data root, SearchCoverage coverage, Runnable deadline) {
        AccessibilityNodeInfo node=new AccessibilityNodeInfo(root), result=null;
        try {
            result=new TextInputTargetResolver(x -> "Synthetic label".equals(x.data.text),deadline,1,coverage).resolve(node);
            return result==null?null:result.data;
        } finally {
            if(result!=null)result.recycle(); node.recycle();
            check(AccessibilityNodeInfo.live==0);
        }
    }
    static Data resolve(Data root) { return resolve(root,new SearchCoverage(),()->{}); }
    static void fails(String expected, Runnable action) {
        try { action.run(); throw new AssertionError("expected "+expected); }
        catch(OperationFailure failure) { check(expected.equals(failure.code)); }
        check(AccessibilityNodeInfo.live==0);
    }
    public static void main(String[] args) {
        Data direct=n("Synthetic label",true);
        check(resolve(n("",false).child(direct))==direct);
        Data edit=n("",true).child(n("Synthetic label",false));
        check(resolve(n("",false).child(edit))==edit);
        // Repeated matches belonging to the same control are deduplicated.
        edit.child(n("Synthetic label",false));
        check(resolve(n("",false).child(edit))==edit);
        Data label=n("Synthetic label",false), related=n("",true);
        label.labelFor=related;
        check(resolve(n("",false).child(label).child(related))==related);
        label.labelFor=null; related.labeledBy=label;
        check(resolve(n("",false).child(label).child(related))==related);
        // Reverse-only relation also works when the label is not a child in the tree.
        check(resolve(n("",false).child(related))==related);
        Data second=n("Synthetic label",true);
        fails("ambiguous_target",()->resolve(n("",false).child(edit).child(second)));
        Data split=n("Synthetic label",false); split.labelFor=second;
        fails("ambiguous_target",()->resolve(n("",false).child(n("",true).child(split)).child(second)));
        fails("not_editable",()->resolve(n("",false).child(n("Synthetic label",false)).child(n("",true))));
        // A generic container matching the selector does not own its editable descendants.
        fails("not_editable",()->resolve(n("Synthetic label",false).child(n("",true))));
        check(resolve(n("",false).child(n("Unrelated",true)))==null);
        fails("tree_unavailable",()->resolve(n("",false).child(direct).child(null)));
        fails("tree_unavailable",()->resolve(n("",false).child(direct),new SearchCoverage(1,64),()->{}));
        fails("tree_unavailable",()->resolve(n("",false).child(direct),new SearchCoverage(10,0),()->{}));
        SearchCoverage shared=new SearchCoverage(2,64);
        check(resolve(n("",false),shared,()->{})==null);
        fails("tree_unavailable",()->resolve(n("",false).child(direct),shared,()->{}));
        Data foreign=n("",true); foreign.window=2;
        Data foreignLabel=n("Synthetic label",false); foreignLabel.labelFor=foreign;
        fails("stale_target",()->resolve(n("",false).child(foreignLabel)));
        Data reverse=n("",true); reverse.labeledBy=foreign;
        fails("stale_target",()->resolve(n("",false).child(reverse)));
        fails("deadline_exceeded",()->resolve(n("",false).child(direct),new SearchCoverage(),
                ()->{throw new OperationFailure("deadline_exceeded",408,"not_applied");}));
        System.out.println("TextInputTargetResolverTest passed");
    }
}
'''
with tempfile.TemporaryDirectory(prefix="henyo-text-target-") as directory:
    path=Path(directory)
    stub=path/'android/view/accessibility/AccessibilityNodeInfo.java'
    stub.parent.mkdir(parents=True)
    stub.write_text(STUB)
    test=path/'TextInputTargetResolverTest.java'
    test.write_text(TEST)
    production=ROOT/'src/main/java/link/liaru/henyo'
    subprocess.run(['javac','--release','11','-d',directory,str(stub),str(test),
                    *[str(production/name) for name in (
                        'TextInputTargetResolver.java','SearchCoverage.java','OperationFailure.java')]],check=True)
    subprocess.run(['java','-cp',directory,'link.liaru.henyo.TextInputTargetResolverTest'],check=True)
