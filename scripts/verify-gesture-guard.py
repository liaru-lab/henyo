#!/usr/bin/env python3
"""Run the production multi-point window guard against changing synthetic windows."""
import subprocess
import tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
service=(root/'src/main/java/link/liaru/henyo/HenyoAccessibilityService.java').read_text()
method=service.split('    private String gestureBlockReason(TargetRef target, int[] xs, int[] ys) {',1)[1].split('    private Match findMatch(',1)[0]
harness=r'''
import java.util.*;
public final class GestureGuardTest {
    static class Rect {
        int left,top,right,bottom;
        Rect() {} Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
        boolean contains(int x,int y){return x>=left&&x<right&&y>=top&&y<bottom;}
        public boolean equals(Object o){if(!(o instanceof Rect))return false;Rect r=(Rect)o;return left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}
        void set(Rect r){left=r.left;top=r.top;right=r.right;bottom=r.bottom;}
    }
    static class Region { Rect bounds=new Rect(0,0,1000,1000);boolean contains(int x,int y){return bounds.contains(x,y);} }
    static class Build {static class VERSION{static int SDK_INT=35;}}
    static class Display{static int DEFAULT_DISPLAY=0;}
    static class AccessibilityNodeInfo{String name; AccessibilityNodeInfo(String s){name=s;}String getPackageName(){return name;}void recycle(){}}
    static class AccessibilityWindowInfo {
        int id,display,layer;String name="example.app";Rect bounds;
        AccessibilityWindowInfo(int id,int display,int layer,Rect b){this.id=id;this.display=display;this.layer=layer;bounds=b;}
        int getId(){return id;}int getDisplayId(){return display;}int getLayer(){return layer;}
        void getBoundsInScreen(Rect out){out.set(bounds);}void getRegionInScreen(Region out){out.bounds=bounds;}
    }
    static class TargetRef{int windowId=1,displayId=0;String packageName="example.app";Rect bounds=new Rect(0,0,1000,1000);Region actionableRegion=new Region();}
    List<AccessibilityWindowInfo> windows=new ArrayList<>();int probes,recycles;
    List<AccessibilityWindowInfo> allWindows(){return windows;}
    AccessibilityNodeInfo windowRoot(AccessibilityWindowInfo w){probes++;return new AccessibilityNodeInfo(w.name);}
    void recycleWindows(List<AccessibilityWindowInfo> ignored){recycles++;}
    String str(Object o){return o==null?"":o.toString();}
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    public static void main(String[] args){
        GestureGuardTest guard=new GestureGuardTest();TargetRef target=new TargetRef();
        AccessibilityWindowInfo own=new AccessibilityWindowInfo(1,0,1,target.bounds);guard.windows.add(own);
        int[] xs={100,200,300,400,500,600,700},ys={100,100,100,100,100,100,100};
        check(guard.gestureBlockReason(target,xs,ys).isEmpty(),"clear path rejected");
        check(guard.probes==1&&guard.recycles==1,"one fresh native root must cover all path samples");
        AccessibilityWindowInfo blocker=new AccessibilityWindowInfo(2,0,2,new Rect(350,50,450,150));guard.windows.add(blocker);
        check(guard.gestureBlockReason(target,xs,ys).equals("target_occluded"),"interior occlusion missed");
        blocker.display=1;check(guard.gestureBlockReason(target,xs,ys).isEmpty(),"other display blocked path");
        own.bounds=new Rect(0,0,900,1000);check(guard.gestureBlockReason(target,xs,ys).equals("stale_target"),"resized target accepted");
        own.bounds=target.bounds;own.name="other.app";check(guard.gestureBlockReason(target,xs,ys).equals("stale_target"),"root identity change accepted");
        own.name="example.app";check(guard.gestureBlockReason(target,new int[]{1000},new int[]{100}).equals("target_outside"),"outside point accepted");
        guard.windows.remove(own);check(guard.gestureBlockReason(target,xs,ys).equals("stale_target"),"missing target accepted");
        System.out.println("GestureGuardTest passed");
    }
    private String gestureBlockReason(TargetRef target,int[] xs,int[] ys){
'''
with tempfile.TemporaryDirectory(prefix='henyo-gesture-') as directory:
    path=Path(directory)/'GestureGuardTest.java';path.write_text(harness+method+'\n}\n')
    subprocess.run(['javac','--release','11','-d',directory,str(path)],check=True)
    subprocess.run(['java','-cp',directory,'GestureGuardTest'],check=True)
