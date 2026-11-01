#!/usr/bin/env python3
"""Check fail-closed inspection and production same-root snapshot assembly."""
import subprocess
import tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
java=root/'src/main/java/link/liaru/henyo'
service=(java/'HenyoAccessibilityService.java').read_text()
read=service[service.index('    private Response uiTree('):service.index('    private Response findTextResponse(')]
current=service[service.index('    private Response currentAppForTarget('):service.index('    private String appList(')]
harness=r'''
package link.liaru.henyo;
import java.util.*;
import java.nio.charset.StandardCharsets;
public final class SnapshotBindingTest {
    static class AccessibilityNodeInfo {
        final String label; final SensitiveTreeInspector.Result sensitivity; int recycled;
        AccessibilityNodeInfo(String label,SensitiveTreeInspector.Result status){this.label=label;sensitivity=status;}
        String getClassName(){return label;} void recycle(){recycled++;}
    }
    static class Response { final byte[] body;Response(String body){this.body=body.getBytes(StandardCharsets.UTF_8);} }
    static class Counter {boolean truncated;Counter(int limit){} }
    static class TargetRef {String toJson(){return "{\"windowId\":7}";} }
    static class TargetSnapshot {
        AccessibilityNodeInfo root;String packageName="example.app";SensitiveTreeInspector.Result sensitivity;
        TargetSnapshot(AccessibilityNodeInfo node){root=node;}TargetRef ref(){return new TargetRef();}
    }
    static class Metrics {PhaseTimings phases=new PhaseTimings();}
    final Metrics performanceMetrics=new Metrics();int resolves,inspections;
    AccessibilityNodeInfo selected=new AccessibilityNodeInfo("SelectedRoot",SensitiveTreeInspector.Result.CLEAR);
    AccessibilityNodeInfo unrelated=new AccessibilityNodeInfo("UnrelatedRoot",SensitiveTreeInspector.Result.SENSITIVE);
    TargetSnapshot resolveTarget(OperationTargetConstraint constraint,boolean remember){resolves++;return new TargetSnapshot(resolves==1?selected:unrelated);}
    SensitiveTreeInspector.Result inspectSensitiveRoot(AccessibilityNodeInfo node){inspections++;return node.sensitivity;}
    Response targetConstraintError(OperationTargetConstraint constraint){return json(400,"invalid");}
    Response json(int code,String body){return new Response(body);}
    String escape(String text){return text;}String str(Object value){return value==null?"":value.toString();}
    boolean boolParam(Map<String,String> params,String key,boolean fallback){return fallback;}
    int intParam(Map<String,String> params,String key,int fallback){return fallback;}
    void appendNode(StringBuilder out,AccessibilityNodeInfo node,int a,int b,int depth,boolean redact,Counter counter){out.append("{\"text\":\"").append(node.label).append("\"}");}
    void appendFlatNodes(StringBuilder out,AccessibilityNodeInfo node,int a,int b,int depth,boolean redact,Counter counter){appendNode(out,node,a,b,depth,redact,counter);}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        SnapshotBindingTest test=new SnapshotBindingTest();
        UiTreeRead result=test.readUiTree(Collections.emptyMap(),true);
        check(test.resolves==1,"snapshot must resolve exactly one target");
        check(new String(result.tree.body,StandardCharsets.UTF_8).contains("SelectedRoot"),"payload uses selected root");
        check(new String(result.current.body,StandardCharsets.UTF_8).contains("SelectedRoot"),"metadata uses same root as payload");
        check(!result.sensitive&&test.inspections==1,"recipient classification belongs to the same root");
        check(test.selected.recycled==1&&test.unrelated.recycled==0,"only the owned root is released");
        test=new SnapshotBindingTest();test.selected=new AccessibilityNodeInfo("UnavailableInspection",SensitiveTreeInspector.Result.UNKNOWN);
        result=test.readUiTree(Collections.emptyMap(),true);
        check(result.sensitive,"unknown inspection restricts background recipients");
        System.out.println("SnapshotBindingTest passed");
    }
'''
node_action=service[service.index('    private boolean controlledNodeAction('):service.index('    private void controlledStartActivity(')]
node_harness=r'''
package link.liaru.henyo;
import java.util.*;
public final class NodeDispatchTest {
    static class Bundle {}
    static class AccessibilityNodeInfo {
        String name="example.app", refreshedName=name;int window=1,refreshedWindow=1,recycled;boolean protectedUi;
        boolean refresh(){name=refreshedName;window=refreshedWindow;return true;}
        int getWindowId(){return window;}String getPackageName(){return name;}
        void recycle(){recycled++;}boolean performAction(int action,Bundle args){return true;}
    }
    static class AccessibilityWindowInfo {int getId(){return 1;}}
    AccessibilityNodeInfo root=new AccessibilityNodeInfo();List<AccessibilityWindowInfo> windows=new ArrayList<>();int dispatches,recycled;
    NodeDispatchTest(){windows.add(new AccessibilityWindowInfo());}
    List<AccessibilityWindowInfo> allWindows(){return windows;}
    AccessibilityNodeInfo windowRoot(AccessibilityWindowInfo window){return root;}
    void recycleWindows(List<AccessibilityWindowInfo> ignored){recycled++;}
    String str(Object value){return value==null?"":value.toString();}
    void requireRootAccess(AccessibilityNodeInfo node){if(node.protectedUi)throw new OperationFailure("sensitive_ui_permission_required",403,"not_applied");}
    void requireWorkflowNodePoint(AccessibilityNodeInfo node,List<AccessibilityWindowInfo> windows,AccessibilityWindowInfo window,AccessibilityNodeInfo root) {}
    boolean controlledDispatch(java.util.function.Supplier<Boolean> action){dispatches++;return action.get();}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        NodeDispatchTest test=new NodeDispatchTest();AccessibilityNodeInfo node=new AccessibilityNodeInfo();
        check(test.controlledNodeAction(node,1,null)&&test.dispatches==1&&test.root.recycled==1&&test.recycled==1,"validated action dispatches once and releases handles");
        test=new NodeDispatchTest();node=new AccessibilityNodeInfo();node.refreshedName="other.app";
        try{test.controlledNodeAction(node,1,null);throw new AssertionError("refreshed identity accepted");}
        catch(OperationFailure e){check(e.code.equals("stale_target")&&test.dispatches==0,"refresh cannot rebind a node to another app");}
        test=new NodeDispatchTest();node=new AccessibilityNodeInfo();node.refreshedWindow=2;
        try{test.controlledNodeAction(node,1,null);throw new AssertionError("new window accepted");}
        catch(OperationFailure e){check(e.code.equals("stale_target")&&test.dispatches==0,"refresh cannot change the matched window");}
        test=new NodeDispatchTest();node=new AccessibilityNodeInfo();test.root.protectedUi=true;
        try{test.controlledNodeAction(node,1,null);throw new AssertionError("protected dispatch accepted");}
        catch(OperationFailure e){check(test.dispatches==0&&test.root.recycled==1&&test.recycled==1,"denial releases the actual root and windows");}
        test=new NodeDispatchTest();node=new AccessibilityNodeInfo();test.root.name="other.app";
        try{test.controlledNodeAction(node,1,null);throw new AssertionError("changed actual root accepted");}
        catch(OperationFailure e){check(e.code.equals("stale_target")&&test.dispatches==0&&test.root.recycled==1,"actual root identity is checked before dispatch");}
        System.out.println("NodeDispatchTest passed");
    }
'''
with tempfile.TemporaryDirectory(prefix='henyo-target-access-') as directory:
    path=Path(directory)/'SnapshotBindingTest.java';path.write_text(harness+read+current+'\n}\n')
    node_path=Path(directory)/'NodeDispatchTest.java';node_path.write_text(node_harness+node_action+'\n}\n')
    subprocess.run(['javac','--release','11','-d',directory,*[str(java/(name+'.java')) for name in (
        'SensitiveTreeInspector','SensitiveUiAccessPolicy','WsOperation','PhaseTimings','OperationTargetConstraint','WindowTargetResolver','TargetHistoryStore','BearerAuthPolicy','OperationFailure')],
        str(root/'src/test/java/link/liaru/henyo/SensitiveTreeInspectorTest.java'),str(path),str(node_path)],check=True)
    for name in ('SensitiveTreeInspectorTest','SnapshotBindingTest','NodeDispatchTest'):
        subprocess.run(['java','-cp',directory,'link.liaru.henyo.'+name],check=True)
