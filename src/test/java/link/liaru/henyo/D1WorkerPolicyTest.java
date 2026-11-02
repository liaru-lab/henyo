package link.liaru.henyo;

public final class D1WorkerPolicyTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        D1WorkerPolicy policy=new D1WorkerPolicy();
        long preparing=policy.epoch();policy.request();
        policy.cancel();
        check(!policy.restartAfter(preparing),"Stop during preparation must not restart a worker in finally");
        check(policy.epoch()!=preparing,"pending decisions expire on disable/Stop");
        policy.request();
        check(policy.restartAfter(preparing),"a new explicit enable/prepare intent can supersede a retiring job");
        check(!policy.restartAfter(policy.epoch()),"a failed current preparation must not retry itself");
        policy.cancel();
        check(!policy.requested(),"idle release and failure clear preparation intent");
        check(!D1WorkerPolicy.releaseIdle("preparing",true,false,0,900000),"startup must survive status polling on an old-uptime device");
        check(!D1WorkerPolicy.releaseIdle("ready",true,false,0,900000),"readiness publication cannot expire a preparing worker");
        check(!D1WorkerPolicy.releaseIdle("ready",false,true,0,900000),"active inference must not be reclaimed");
        check(!D1WorkerPolicy.releaseIdle("ready",false,false,600001,900000),"recently used model remains warm");
        check(D1WorkerPolicy.releaseIdle("ready",false,false,0,900000),"an idle ready worker is reclaimed");
        System.out.println("D1WorkerPolicyTest passed");
    }
}
