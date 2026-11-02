package link.liaru.henyo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional, app-owned native worker. Model inference never dispatches UI input. */
final class D1Runtime {
    static final long MODEL_BYTES = 1099629888L;
    static final String MODEL_SHA = "adca5789ad30ae6027e8e936749bb9f19673097492ab26fa1c5b69643ca50b4b";
    static final String MODEL_URL = "https://huggingface.co/LiquidAI/d1-2B-GGUF/resolve/e968f6575f0d4fad928312d7af15cad90fd365c0/d1-2B-Q4_K_M.gguf";
    private static D1Runtime instance;
    static synchronized D1Runtime get(Context context) {
        if (instance == null) instance = new D1Runtime(context.getApplicationContext());
        return instance;
    }
    static final class Failure extends RuntimeException {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    private final Context context;
    private final SharedPreferences prefs;
    private final File directory, model;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final D1WorkerPolicy lifecycle = new D1WorkerPolicy();
    private final Set<Process> retiring = new HashSet<>();
    private boolean shutdownFailed;
    private final android.os.Handler idleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idleExpiry = new Runnable() {
        public void run() {
            idleCheck();
            if (process != null) idleHandler.postDelayed(this, 300000L);
        }
    };
    private volatile Process process;
    private volatile String state = "disabled", error = "";
    private volatile long downloaded, startupMs, lastApiMs, lastUse;
    private volatile int port;
    private volatile String key;
    private volatile boolean preparing;
    private D1Runtime(Context context) {
        this.context = context; prefs = context.getSharedPreferences("assistance", Context.MODE_PRIVATE);
        directory = new File(context.getFilesDir(), "d1"); model = new File(directory, "model.gguf");
        if (enabled()) state = model.isFile() ? "idle" : "missing_model";
    }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    long epoch() { return lifecycle.epoch(); }
    boolean packaged() { return new File(context.getApplicationInfo().nativeLibraryDir, "libhenyo-d1.so").isFile(); }
    synchronized void setEnabled(boolean value) {
        prefs.edit().putBoolean("enabled", value).apply();
        lifecycle.cancel(); stopWorker(); error = "";
        state = value ? (model.isFile() ? "idle" : "missing_model") : "disabled";
        if (value && model.isFile()) prepare();
    }
    synchronized void resume() { if (enabled() && model.isFile() && !controlStopped()) prepare(); }
    private boolean controlStopped() { return context.getSharedPreferences("control",Context.MODE_PRIVATE).getBoolean("stopped",false); }
    synchronized void stopWorker() {
        idleHandler.removeCallbacks(idleExpiry);
        Process old = process; process = null;
        if (old == null || !old.isAlive()) return;
        retiring.add(old);old.destroy();
        new Thread(() -> {
            boolean exited=false;
            try {
                exited=old.waitFor(1,java.util.concurrent.TimeUnit.SECONDS);
                if(!exited){old.destroyForcibly();exited=old.waitFor(2,java.util.concurrent.TimeUnit.SECONDS);}
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();old.destroyForcibly();}
            synchronized(this) {
                if(exited||!old.isAlive())retiring.remove(old);
                else shutdownFailed=true;
                if(lifecycle.requested()&&enabled()&&!preparing&&!busy.get()&&process==null) {
                    if(shutdownFailed&&!retiring.isEmpty()){state="unavailable";error="worker_shutdown_timeout";}
                    else prepare();
                }
            }
        },"henyo-d1-reap").start();
    }
    synchronized void suspend() {
        lifecycle.cancel();stopWorker();
        state = enabled() ? (model.isFile() ? "idle" : "missing_model") : "disabled";
    }
    synchronized void idleCheck() {
        if (process != null && D1WorkerPolicy.releaseIdle(state,preparing,busy.get(),lastUse,SystemClock.elapsedRealtime())) {
            lifecycle.cancel();stopWorker();state = "idle";
        }
    }
    private void check(long ticket) {
        if (!enabled() || lifecycle.epoch() != ticket) throw new Failure("assistance_disabled");
    }
    synchronized void prepare() {
        if (!enabled() || controlStopped()) return;
        lifecycle.request();
        if (preparing || busy.get() || "downloading".equals(state)
                || ("ready".equals(state) && process!=null && process.isAlive())) return;
        stopWorker();
        retiring.removeIf(old -> !old.isAlive());
        if(!retiring.isEmpty()){state=shutdownFailed?"unavailable":"preparing";error=shutdownFailed?"worker_shutdown_timeout":"";return;}
        shutdownFailed=false;
        if (!packaged()) { state="unavailable";error="runtime_missing";return; }
        if (!model.isFile()) { state="missing_model";return; }
        preparing=true; state="preparing"; long ticket=lifecycle.epoch();
        new Thread(() -> {
            Process started = null;
            try {
                check(ticket); verifyModel(model, ticket);
                File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
                JSONObject manifest;
                try(InputStream stream=context.getAssets().open("d1-runtime.json")) {
                    manifest = new JSONObject(new String(readLimited(stream,32768),StandardCharsets.UTF_8));
                }
                if (!"henyo.d1.htp-v79/1".equals(manifest.getString("contract"))) throw new Failure("runtime_mismatch");
                JSONObject files=manifest.getJSONObject("files");
                for(Iterator<String> names=files.keys();names.hasNext();) {
                    String name=names.next();
                    if(!name.matches("lib[A-Za-z0-9+_.-]+\\.so") || !sha(new File(nativeDir,name)).equals(files.getString(name)))
                        throw new Failure("runtime_mismatch");
                    check(ticket);
                }
                directory.mkdirs(); File log=new File(directory,"worker.log");
                int selectedPort;
                try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { selectedPort=socket.getLocalPort(); }
                byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);String selectedKey=hex(secret);
                List<String> args=new ArrayList<>(Arrays.asList(new File(nativeDir,"libhenyo-d1.so").getPath(),
                    "-m",model.getPath(),"-c","2048","-b","2048","-ub","2048","-t","4","-tb","4","-np","1",
                    "--no-warmup","-lv","4","--device","HTPT0","--gpu-layers","999","--host","127.0.0.1",
                    "--port",String.valueOf(selectedPort),"--no-webui","--timeout","33","--api-key",selectedKey));
                ProcessBuilder builder=nativeBuilder(args,nativeDir,log);
                long begin=SystemClock.elapsedRealtime();
                synchronized(this) { check(ticket); started=builder.start();process=started;port=selectedPort;key=selectedKey; }
                long deadline=begin+120000L;
                while(true) {
                    check(ticket);if(!started.isAlive())throw new Failure("native_worker_failed");
                    if(SystemClock.elapsedRealtime()>=deadline)throw new Failure("startup_timeout");
                    try { exchange("GET","/health",null,500,()->check(ticket));break; }
                    catch(IOException notReady) { Thread.sleep(100); }
                }
                String text;
                try(InputStream stream=new FileInputStream(log)) { text=new String(readLimited(stream,1048576),StandardCharsets.UTF_8); }
                if(!text.contains("Hexagon Arch version v79")||!text.contains("offloaded 25/25 layers")
                    ||!text.contains("HTPT0 model buffer size")||!text.contains("build 11960 (8642541b2)"))throw new Failure("backend_unavailable");
                synchronized(this) {
                    check(ticket);startupMs=SystemClock.elapsedRealtime()-begin;lastUse=SystemClock.elapsedRealtime();state="ready";error="";
                    idleHandler.removeCallbacks(idleExpiry);idleHandler.postDelayed(idleExpiry,300000L);
                }
            } catch(Exception failure) {
                android.util.Log.w("HenyoD1", "Preparation failed: " + (failure instanceof Failure ? ((Failure)failure).code : failure.getClass().getSimpleName()));
                synchronized(this) {
                    if(lifecycle.epoch()==ticket) {lifecycle.cancel();stopWorker();state=enabled()?"unavailable":"disabled";error=failure instanceof Failure?((Failure)failure).code:"native_worker_failed";}
                }
            } finally { synchronized(this){preparing=false;if(lifecycle.restartAfter(ticket)&&enabled())prepare();} }
        },"henyo-d1-prepare").start();
    }
    private ProcessBuilder nativeBuilder(List<String> args,File nativeDir,File log) {
        ProcessBuilder builder=new ProcessBuilder(args).directory(directory).redirectErrorStream(true).redirectOutput(log);
        Map<String,String> env=builder.environment();
        for(String name:new ArrayList<>(env.keySet()))if(name.startsWith("GGML_")||name.startsWith("HENYO_D1_")||name.equals("LD_PRELOAD"))env.remove(name);
        env.put("LD_LIBRARY_PATH",nativeDir.getPath()+":/system/lib64:/vendor/lib64");
        env.put("ADSP_LIBRARY_PATH",nativeDir.getPath()+";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/dsp");
        env.put("GGML_BACKEND_PATH",new File(nativeDir,"libggml-hexagon-tuned.so").getPath());
        env.put("GGML_HEXAGON_OPPOLL","1");env.put("GGML_HEXAGON_POWER_MODE","sustained_high_performance");
        String[][] flags={{"NPU_STENCIL","1"},{"PAIR_GLU","1"},{"PAIR_MAX_TOKENS","0"},{"SELECT_LAST","1"},
            {"SELECT_FFN","1"},{"SELECT_PAD","1"},{"SELECT_MIN_TOKENS","256"},{"RESIDUAL_NORM","1"},
            {"HMX_SCOPE","down"},{"HMX_M","0"},{"HMX_N","adaptive"},{"GATE_LONG_TUNE","1"}};
        for(String[] flag:flags)env.put("HENYO_D1_"+flag[0],flag[1]);
        env.put("HENYO_D1_OWNER_PID",String.valueOf(android.os.Process.myPid()));
        return builder;
    }
    synchronized void download() {
        if(!enabled()||preparing||busy.get()||"downloading".equals(state))return;
        if(!packaged()){state="unavailable";error="runtime_missing";return;}
        if(model.isFile()&&!"model_mismatch".equals(error)){prepare();return;}
        stopWorker();
        retiring.removeIf(old -> !old.isAlive());
        if(!retiring.isEmpty()){state="unavailable";error="worker_shutdown_pending";return;}
        lifecycle.cancel();final long ticket=lifecycle.epoch();state="downloading";error="";downloaded=0;
        new Thread(()-> {
            File partial=new File(directory,"model-"+ticket+".part");
            try {
                directory.mkdirs();
                // Discover the actual accelerator before downloading a large model.
                File nativeDir=new File(context.getApplicationInfo().nativeLibraryDir),log=new File(directory,"worker.log");
                Process probe;
                synchronized(this) {
                    check(ticket);
                    probe=nativeBuilder(Arrays.asList(new File(nativeDir,"libhenyo-d1.so").getPath(),"-lv","4","--list-devices"),nativeDir,log).start();
                    process=probe;
                }
                boolean exited=probe.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);
                synchronized(this) {
                    if(process==probe){if(!exited)stopWorker();else process=null;}
                }
                check(ticket);
                if(!exited||probe.exitValue()!=0)throw new Failure("backend_unavailable");
                String probeLog;
                try(InputStream input=new FileInputStream(log)){probeLog=new String(readLimited(input,65536),StandardCharsets.UTF_8);}
                if(!probeLog.contains("Hexagon Arch version v79")||!probeLog.contains("HTPT0:"))throw new Failure("backend_unavailable");
                URL url=new URL(MODEL_URL);
                HttpURLConnection connection=(HttpURLConnection)url.openConnection();
                connection.setConnectTimeout(15000);connection.setReadTimeout(15000);
                try(InputStream input=connection.getInputStream();OutputStream output=new FileOutputStream(partial)) {
                    byte[] buffer=new byte[262144];int count;long received=0;
                    while((count=input.read(buffer))!=-1) {
                        check(ticket);received+=count;if(received>MODEL_BYTES)throw new Failure("model_mismatch");
                        output.write(buffer,0,count);
                        synchronized(this){check(ticket);downloaded=received;}
                    }
                } finally {connection.disconnect();}
                verifyModel(partial,ticket);check(ticket);
                synchronized(this) {check(ticket);if(!partial.renameTo(model))throw new IOException("model move failed");state="idle";prepare();}
            } catch(Exception failure) {
                synchronized(this){if(lifecycle.epoch()==ticket){state=enabled()?"unavailable":"disabled";error=failure instanceof Failure?((Failure)failure).code:"download_failed";}}
            } finally { if(partial.exists())partial.delete(); }
        },"henyo-d1-download").start();
    }
    private void verifyModel(File file,long ticket)throws Exception {
        check(ticket);if(file.length()!=MODEL_BYTES||!sha(file).equals(MODEL_SHA))throw new Failure("model_mismatch");check(ticket);
    }
    JSONObject evaluate(JSONObject body,Runnable guard) {
        final long ticket;
        synchronized(this) {
            if(!enabled())throw new Failure("assistance_disabled");
            if(!"ready".equals(state)||process==null||!process.isAlive())throw new Failure("assistance_not_ready");
            if(!busy.compareAndSet(false,true))throw new Failure("assistance_busy");
            ticket=lifecycle.epoch();
        }
        try {
            if(!(body.opt("state") instanceof JSONObject))throw new Failure("assistance_invalid");
            JSONObject questions=body.getJSONObject("questions");
            if(questions.length()!=1)throw new Failure("assistance_invalid");
            String type=questions.getJSONObject(questions.keys().next()).getString("type");
            if(!Arrays.asList("noul","choice","score").contains(type))throw new Failure("assistance_invalid");
            byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
            if(payload.length>32768)throw new Failure("assistance_invalid");
            Runnable check=()-> {check(ticket);guard.run();};check.run();long begin=SystemClock.elapsedRealtime();
            JSONObject result=new JSONObject(new String(exchange("POST","/v1/systemone",payload,30000,check),StandardCharsets.UTF_8));
            check.run();lastApiMs=SystemClock.elapsedRealtime()-begin;lastUse=SystemClock.elapsedRealtime();
            JSONObject answers=result.getJSONObject("answers");
            if(answers.length()!=1||!answers.has(questions.keys().next()))throw new Failure("native_response_invalid");
            return result;
        }catch(Failure failure){throw failure;}
        catch(org.json.JSONException invalid){throw new Failure("assistance_invalid");}
        catch(RuntimeException cancelled){synchronized(this){if(lifecycle.epoch()==ticket){lifecycle.cancel();stopWorker();state=enabled()?"idle":"disabled";}}throw cancelled;}
        catch(Exception failure){
            boolean expired;
            synchronized(this){
                expired=lifecycle.epoch()!=ticket||!enabled();
                if(!expired){lifecycle.cancel();stopWorker();state="unavailable";error="inference_failed";}
            }
            throw new Failure(expired?"assistance_disabled":"inference_failed");
        }
        finally{
            busy.set(false);
            synchronized(this){if(lifecycle.requested()&&enabled()&&process==null&&!preparing)prepare();}
        }
    }
    private byte[] exchange(String method,String path,byte[] body,int timeout,Runnable guard)throws IOException {
        long deadline=SystemClock.elapsedRealtime()+timeout;
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",port),Math.min(timeout,500));socket.setSoTimeout(100);
            OutputStream output=socket.getOutputStream();
            String headers=method+" "+path+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+key+"\r\nConnection: close\r\n";
            if(body!=null)headers+="Content-Type: application/json\r\nContent-Length: "+body.length+"\r\n";
            output.write((headers+"\r\n").getBytes(StandardCharsets.US_ASCII));if(body!=null)output.write(body);output.flush();
            InputStream input=socket.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];
            while(true) {
                guard.run();if(SystemClock.elapsedRealtime()>deadline)throw new SocketTimeoutException("native deadline");
                int count;try{count=input.read(buffer);}catch(SocketTimeoutException wait){continue;}
                if(count<0)break;bytes.write(buffer,0,count);if(bytes.size()>81920)throw new IOException("native response limit");
            }
            byte[] raw=bytes.toByteArray();String text=new String(raw,StandardCharsets.UTF_8);int split=text.indexOf("\r\n\r\n");
            if(split>=0&&"/v1/systemone".equals(path)) {
                if(text.startsWith("HTTP/1.1 400 ")||text.startsWith("HTTP/1.1 413 "))
                    throw new Failure(text.substring(split+4).contains("exceed_context_size_error")
                            ?"assistance_context_limit":"assistance_invalid");
                // The pinned decision server reports its physical-batch limit as
                // HTTP 500, although the model/worker itself remains healthy.
                if(text.startsWith("HTTP/1.1 500 ")) {
                    try {
                        JSONObject detail=new JSONObject(text.substring(split+4)).getJSONObject("error");
                        if("server_error".equals(detail.optString("type"))&&detail.optString("message").matches(
                                "input \\([0-9]+ tokens\\) is too large to process\\. increase the physical batch size \\(current batch size: [0-9]+\\)"))
                            throw new Failure("assistance_context_limit");
                    }catch(org.json.JSONException invalid){ /* Other server errors retire the worker below. */ }
                }
            }
            if(split<0||!text.startsWith("HTTP/1.1 200 ")||text.substring(0,split).toLowerCase(Locale.ROOT).contains("transfer-encoding:"))throw new IOException("native HTTP response");
            int headerBytes=text.substring(0,split+4).getBytes(StandardCharsets.UTF_8).length;
            if(raw.length-headerBytes>65536)throw new IOException("native response limit");
            return Arrays.copyOfRange(raw,headerBytes,raw.length);
        }
    }
    synchronized String statusJson() {
        idleCheck();
        if(process!=null&&!process.isAlive()&&"ready".equals(state)){state="unavailable";error="native_worker_failed";}
        return "{\"enabled\":"+enabled()+",\"state\":\""+state+"\",\"runtimeAvailable\":"+packaged()+
            ",\"modelAvailable\":"+(model.length()==MODEL_BYTES)+",\"modelBytes\":"+MODEL_BYTES+
            ",\"downloadedBytes\":"+downloaded+",\"startupMs\":"+startupMs+",\"lastApiMs\":"+lastApiMs+
            ",\"error\":\""+error+"\"}";
    }
    String statusText() {
        statusJson();
        if(!packaged())return "Unavailable on this build or device";
        if("downloading".equals(state))return "Downloading model: "+(downloaded*100/MODEL_BYTES)+"%";
        if("ready".equals(state))return "Ready — on-device NPU";
        if("missing_model".equals(state))return "Model not downloaded (1.02 GiB)";
        if("unavailable".equals(state)) {
            if("backend_unavailable".equals(error))return "This device does not support the included accelerator";
            if("model_mismatch".equals(error))return "Model verification failed — download the model again";
            if("download_failed".equals(error))return "Download failed — try again";
            if("worker_shutdown_timeout".equals(error))return "Model could not stop — restart Henyo before preparing again";
            return "Model unavailable — try preparing it again";
        }
        if("preparing".equals(state))return "Preparing…";
        return "disabled".equals(state)?"Disabled":"Idle — prepares when requested";
    }
    void dump(PrintWriter writer) {
        writer.println(statusJson());
        if(!"ready".equals(state)) {
            File log=new File(directory,"worker.log");
            try(RandomAccessFile input=new RandomAccessFile(log,"r")) {
                long begin=Math.max(0,input.length()-16384);input.seek(begin);
                byte[] bytes=new byte[(int)(input.length()-begin)];input.readFully(bytes);
                writer.println(new String(bytes,StandardCharsets.UTF_8));
            }catch(IOException absent){writer.println("No native startup log available.");}
        }
    }
    private static byte[] readLimited(InputStream input,int limit)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int count;
        while((count=input.read(bytes))!=-1){out.write(bytes,0,count);if(out.size()>limit)throw new IOException("size limit");}return out.toByteArray();
    }
    private static String sha(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[262144];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
}
