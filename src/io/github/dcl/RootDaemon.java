package io.github.dcl;

import android.content.Context;
import android.media.*;
import android.net.*;
import android.os.*;
import io.github.dcl.app.BuildIdentity;
import io.github.dcl.app.RootClient;
import org.json.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Single application-owned root service; no code is injected into the audio server. */
public final class RootDaemon {
    static final String APP="io.github.dcl.app", BASE="/data/adb/dolby_control_app/";
    static Handler handler;
    static Object spatializer;
    static Context context;
    static JSONObject policy=new JSONObject(), table, current=new JSONObject();
    static String apk, enhancement="unknown";
    static int ownerUid, soundAliveUid;
    static boolean stopping=false, tableRestorePending=false;
    static LocalServerSocket server;
    static long lastProbe;
    static final ExecutorService clients=Executors.newFixedThreadPool(3);

    static void safeDirectory(File path)throws Exception {
        if(!path.getCanonicalPath().equals(path.getAbsolutePath()))throw new IOException("Unsafe service directory");
        if(!path.isDirectory()&&!path.mkdirs())throw new IOException("Cannot create service directory");
        path.setReadable(false,false);path.setWritable(false,false);path.setExecutable(false,false);
        path.setReadable(true,true);path.setWritable(true,true);path.setExecutable(true,true);
    }
    static void persist()throws Exception {
        File target=new File(BASE+"policy.json"),temp=new File(BASE+"policy.new");
        if(Files.isSymbolicLink(target.toPath())||Files.isSymbolicLink(temp.toPath()))throw new IOException("Unsafe policy path");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(policy.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        temp.setReadable(false,false);temp.setWritable(false,false);temp.setReadable(true,true);temp.setWritable(true,true);
        if(!temp.renameTo(target))throw new IOException("Cannot save sound settings");
    }
    static void installHelper()throws Exception {
        safeDirectory(new File(BASE+"bin"));
        File helper=new File(RuntimeEdit.HELPER);
        if(Files.isSymbolicLink(helper.toPath()))throw new IOException("Unsafe helper path");
        byte[] bytes;
        try(ZipFile zip=new ZipFile(apk);InputStream in=zip.getInputStream(zip.getEntry("assets/backend/table-runtime"))){bytes=in.readAllBytes();}
        StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))digest.append(String.format(Locale.ROOT,"%02x",b&255));
        if(!BuildIdentity.NATIVE_SHA256.equals(digest.toString()))throw new IOException("Audio adapter identity mismatch");
        try(FileOutputStream out=new FileOutputStream(helper)){out.write(bytes);out.getFD().sync();}
        helper.setReadable(false,false);helper.setWritable(false,false);helper.setExecutable(false,false);
        helper.setReadable(true,true);helper.setWritable(true,true);helper.setExecutable(true,true);
    }
    static String shortError(Throwable t) {
        while(t instanceof java.lang.reflect.InvocationTargetException&&t.getCause()!=null)t=t.getCause();
        String value=t.getMessage();return value==null?t.getClass().getSimpleName():value;
    }
    static boolean active(JSONObject s){return s.optBoolean("enabled")&&s.optBoolean("available")&&s.optInt("output")>0&&s.optInt("profile",-1)>=0;}
    static int selected(){return policy.optInt("selection",-1);}
    static String profileEnhancement(int profile)throws Exception {
        if(table==null||!RuntimeControl.editableProfile(profile))return "unknown";
        boolean neutral=true,original=true,hasFlags=false;
        JSONArray records=table.getJSONArray("records");
        for(int i=0;i<records.length();i++){
            JSONObject r=records.getJSONObject(i);int scope=r.getInt("profile");
            if(scope!=profile&&scope!=-1)continue;
            int value=r.getInt("current");
            if(r.getBoolean("is_flag")){hasFlags=true;neutral&=value==0;}
            original&=value==r.getInt("original");
        }
        return !hasFlags?"unknown":neutral?"neutral":original?"preset":"custom";
    }
    static JSONObject snapshot()throws Exception {
        JSONObject s=RuntimeControl.state(spatializer);int p=s.optInt("profile",-1);boolean active=active(s);
        enhancement=profileEnhancement(RuntimeControl.editableProfile(p)?p:RuntimeControl.editableProfile(selected())?selected():2);
        current=new JSONObject().put("ok",true).put("ready",true).put("build",BuildIdentity.TOKEN)
            .put("apk",apk)
            .put("active",active).put("profile",p).put("selected_profile",selected())
            .put("enhancement",enhancement).put("enhancement_supported",table!=null&&RuntimeControl.editableProfile(p))
            .put("enabled",s.optBoolean("enabled")).put("available",s.optBoolean("available"));
        return new JSONObject(current.toString());
    }
    static void rememberTable(JSONObject t)throws Exception {
        table=t;JSONArray edits=new JSONArray(),records=t.getJSONArray("records");
        boolean neutral=true,original=true;
        for(int i=0;i<records.length();i++){
            JSONObject r=records.getJSONObject(i);int value=r.getInt("current");
            edits.put(new JSONObject().put("profile",r.getInt("profile")).put("key",r.getString("key")).put("value",value));
            if(r.getBoolean("is_flag"))neutral&=value==0;
            original&=value==r.getInt("original");
        }
        enhancement=neutral?"neutral":original?"preset":"custom";tableRestorePending=false;
        policy.put("edits",edits).put("enhancement",enhancement);persist();
    }
    static JSONObject edits(JSONArray values)throws Exception {
        if(values.length()<1||values.length()>128)throw new IOException("调整数量不正确");
        StringBuilder batch=new StringBuilder();
        for(int i=0;i<values.length();i++){
            JSONObject e=values.getJSONObject(i);int p=e.getInt("profile"),v=e.getInt("value");String key=e.getString("key");
            if((p!=-1&&!RuntimeControl.editableProfile(p))||!key.matches("[a-z][a-z0-9-]{2,63}"))throw new IOException("不支持的音效参数");
            batch.append(p).append('\t').append(key).append('\t').append(v).append('\n');
        }
        String input=Base64.getEncoder().encodeToString(batch.toString().getBytes(StandardCharsets.UTF_8));
        try(ProfileLock lock=new ProfileLock()){return RuntimeEdit.run(new String[]{input});}
    }
    static boolean sameAudioProcess() {
        if(table==null)return false;
        try{
            String stat=Files.readString(Path.of("/proc/"+table.getInt("pid")+"/stat"));
            String[] fields=stat.substring(stat.lastIndexOf(')')+2).split(" ");
            return fields[19].equals(table.getString("process_starttime"));
        }catch(Exception ignored){return false;}
    }
    static void restoreAfterAudioRestart() {
        try{
            boolean same=sameAudioProcess();
            if(same&&!tableRestorePending)return;
            if(!same)tableRestorePending=true;
            if(SystemClock.elapsedRealtime()-lastProbe<3000)return;
            lastProbe=SystemClock.elapsedRealtime();
            JSONObject fresh=RuntimeEdit.nativeCall(Arrays.asList("status"),null);
            JSONArray desired=policy.optJSONArray("edits");
            if(desired!=null&&desired.length()>0){
                JSONObject result=edits(desired);
                if(!result.optBoolean("ok"))throw new IOException(result.optString("error"));
                rememberTable(result.getJSONObject("table"));
            }else rememberTable(fresh);
            tableRestorePending=false;
        }catch(Exception e){System.err.println("Restore pending: "+shortError(e));}
    }
    static JSONObject reassert()throws Exception {
        JSONObject before=RuntimeControl.state(spatializer);int goal=selected();
        if(goal>0&&active(before)&&before.optInt("profile",-1)!=goal&&dolbyEnabled()){
            try(ProfileLock lock=new ProfileLock()){
                JSONObject fresh=RuntimeControl.state(spatializer);
                if(active(fresh)&&dolbyEnabled()&&selected()==goal)RuntimeControl.profile(spatializer,goal);
            }
        }
        return snapshot();
    }
    static boolean dolbyEnabled() {
        // Query the live provider, not cached preference XML; never turn Dolby on.
        try{return DolbyPreference.mode()!=5;}catch(Exception ignored){return false;}
    }
    static JSONObject dispatch(String action,JSONObject args,boolean appCaller)throws Exception {
        switch(action){
        case "status":return snapshot();
        case "manual":{
            int p=args.getInt("profile");if(p!=0&&p!=1&&p!=2&&p!=3&&p!=8)throw new IOException("不支持的档位");
            policy.put("selection",p);persist();return reassert();
        }
        case "reassert":return reassert();
        default:if(!appCaller)throw new SecurityException("Operation is private to the settings app");
        }
        if(action.equals("settings")){
            table=RuntimeEdit.nativeCall(Arrays.asList("status"),null);JSONObject state=snapshot();
            int p=state.getInt("profile");if(p<0)p=RuntimeControl.editableProfile(selected())?selected():2;
            return state.put("profile",p).put("records",table.getJSONArray("records"));
        }
        if(action.equals("enhancement")||action.equals("edit")){
            JSONObject result;
            if(action.equals("enhancement")){
                String mode=args.getString("mode");if(!mode.equals("neutral")&&!mode.equals("preset"))throw new IOException("不支持的增强选项");
                try(ProfileLock lock=new ProfileLock()){result=RuntimeEdit.run(new String[]{mode.equals("neutral")?"on":"off"});}
            }else result=edits(args.getJSONArray("edits"));
            if(!result.optBoolean("ok"))return new JSONObject().put("ok",false).put("error",result.optString("error","调整未完成"));
            rememberTable(result.getJSONObject("table"));
            JSONObject state=snapshot().put("applied",true).put("pending",!result.optJSONObject("reload").optBoolean("profile_reloaded")).put("dsp_observed",result.optBoolean("dsp_setters_observed"));
            if(action.equals("edit")){int p=state.getInt("profile");if(p<0)p=RuntimeControl.editableProfile(selected())?selected():2;state.put("profile",p).put("records",table.getJSONArray("records"));}
            return state;
        }
        if(action.equals("shutdown")){stopping=true;handler.postDelayed(()->System.exit(0),150);return new JSONObject().put("ok",true);}
        throw new IOException("不支持的操作");
    }
    static void serve(LocalSocket socket) {
        try(LocalSocket client=socket){
            client.setSoTimeout(5000);Credentials peer=client.getPeerCredentials();int uid=peer.getUid();
            boolean owner=uid==ownerUid||uid==0;
            if(!owner){
                if(uid!=soundAliveUid)throw new SecurityException("Untrusted caller");
                String name=new String(Files.readAllBytes(Path.of("/proc/"+peer.getPid()+"/cmdline")),StandardCharsets.UTF_8).split("\u0000",2)[0];
                if(!name.equals("com.sec.android.app.soundalive"))throw new SecurityException("Unexpected system caller");
            }
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();InputStream input=client.getInputStream();
            for(int b;(b=input.read())!=-1&&b!='\n';){if(bytes.size()>=32768)throw new IOException("Request too large");bytes.write(b);}
            JSONObject request=new JSONObject(bytes.toString("UTF-8"));String action=request.getString("action");JSONObject args=request.optJSONObject("args");
            FutureTask<JSONObject> task=new FutureTask<>(()->dispatch(action,args==null?new JSONObject():args,owner));handler.post(task);
            JSONObject result;
            try{result=task.get(18,TimeUnit.SECONDS);}catch(Exception e){Throwable cause=e instanceof ExecutionException?e.getCause():e;result=new JSONObject().put("ok",false).put("error",shortError(cause));}
            client.getOutputStream().write((result.toString()+"\n").getBytes(StandardCharsets.UTF_8));client.getOutputStream().flush();
        }catch(Exception e){System.err.println("Request rejected: "+shortError(e));}
    }
    public static void main(String[] args)throws Exception {
        if(android.os.Process.myUid()!=0||args.length!=0)throw new SecurityException("Root service only");
        safeDirectory(new File(BASE));
        try(RandomAccessFile singleton=new RandomAccessFile(BASE+"daemon.lock","rw");FileLock lock=singleton.getChannel().tryLock()){
            if(lock==null)return;
            RuntimeControl.verify();spatializer=RuntimeControl.spat();context=RuntimeControl.systemContext;
            android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(APP,0);ownerUid=info.uid;apk=info.sourceDir;
            soundAliveUid=context.getPackageManager().getApplicationInfo("com.sec.android.app.soundalive",0).uid;
            if(!apk.equals(System.getenv("CLASSPATH")))throw new SecurityException("Installed application identity changed");
            DolbyPreference.context=context;installHelper();
            File saved=new File(BASE+"policy.json");if(saved.isFile()&&!Files.isSymbolicLink(saved.toPath())&&saved.length()<65536)policy=new JSONObject(Files.readString(saved.toPath()));
            tableRestorePending=policy.has("edits");enhancement="unknown";
            handler=new Handler(Looper.getMainLooper());
            try{table=RuntimeEdit.nativeCall(Arrays.asList("status"),null);if(!policy.has("edits"))rememberTable(table);}catch(Exception e){System.err.println(shortError(e));}
            snapshot();server=new LocalServerSocket(RootClient.SOCKET);
            Thread listener=new Thread(()->{while(!stopping){try{LocalSocket socket=server.accept();clients.execute(()->serve(socket));}catch(Exception e){if(!stopping)System.err.println(shortError(e));}}},"dcl-control");listener.setDaemon(true);listener.start();
            AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            audio.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback(){@Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs){for(long delay:new long[]{0,80,250,800})handler.postDelayed(()->{try{reassert();}catch(Exception ignored){}},delay);}},handler);
            handler.postDelayed(new Runnable(){int cycles;@Override public void run(){
                try{restoreAfterAudioRestart();reassert();if(++cycles%30==0&&!apk.equals(context.getPackageManager().getApplicationInfo(APP,0).sourceDir))System.exit(0);}catch(Exception ignored){}
                handler.postDelayed(this,1000);
            }},1000);
            Looper.loop();
        }
    }
}
