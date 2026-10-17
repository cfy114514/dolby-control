package io.github.dcl;
import java.util.*;
import java.io.*;
import java.nio.*;
import java.text.*;
import org.json.*;

/** Fixed-record editor; user input never supplies an address, path or command. */
public final class RuntimeEdit {
 static final String BASE="/data/adb/dolby_control_app/", HELPER="/data/adb/dolby_control_app/bin/table-runtime";
 static String run(List<String> args,String identity)throws Exception{
  ProcessBuilder pb=new ProcessBuilder(args).redirectErrorStream(true);if(identity!=null)pb.environment().put("DCL_EXPECT_PROCESS",identity);
  Process p=pb.start();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;
  try(InputStream in=p.getInputStream()){while((n=in.read(block))!=-1){if(out.size()+n>2097152){p.destroy();throw new Exception("Oversize command output");}out.write(block,0,n);}}
  int rc=p.waitFor();String s=out.toString("UTF-8");if(rc!=0)throw new Exception("Command failed ("+rc+"): "+s);return s;
 }
 static JSONObject nativeCall(List<String> args,String identity)throws Exception{List<String> cmd=new ArrayList<>();cmd.add(HELPER);cmd.addAll(args);JSONObject v=new JSONObject(run(cmd,identity));if(!v.getBoolean("ok"))throw new Exception(v.toString());return v;}
 static String identity(JSONObject s)throws Exception{return s.getInt("pid")+":"+s.getString("process_starttime")+":"+s.getString("boot_id");}
 static void save(String name,JSONObject value)throws Exception{
  File tmp=new File(BASE+name+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write((value.toString()+"\n").getBytes("UTF-8"));out.getFD().sync();}
  if(!tmp.renameTo(new File(BASE+name)))throw new Exception("Receipt rename failed");
 }
 static List<String> snapshot(JSONArray records)throws Exception{List<String> a=new ArrayList<>();a.add("set");for(int i=0;i<records.length();i++){JSONObject r=records.getJSONObject(i);a.add(String.valueOf(r.getInt("profile")));a.add(r.getString("key"));a.add(String.valueOf(r.getInt("current")));}return a;}
 static JSONObject reload(Object s,boolean reset)throws Exception{
  JSONObject b=RuntimeControl.state(s);int original=b.getInt("profile");Exception failure=null;
  try{RuntimeControl.profile(s,original==3?2:3);}catch(Exception e){failure=e;}
  try{RuntimeControl.profile(s,original);}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
  if(failure!=null)throw failure;
  if(reset)s.getClass().getMethod("setEffectParameter",int.class,byte[].class).invoke(s,0x10c,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array());
  JSONObject a=RuntimeControl.state(s);if(!a.getBoolean("enabled")||a.getInt("profile")!=original||a.getInt("output")!=b.getInt("output"))throw new Exception("Route or Spatializer state changed");
  return new JSONObject().put("before",b).put("after",a).put("profile_reloaded",true).put("specific_overrides_reset",true).put("common_parameters_reloaded",reset);
 }
 public static void main(String[] args)throws Exception{
  try(ProfileLock operation=new ProfileLock()){JSONObject value=run(args);System.out.println(value);if(!value.optBoolean("ok"))System.exit(4);}
 }
 static JSONObject run(String[] args)throws Exception{
  JSONObject out=new JSONObject().put("ok",false);JSONObject before=null;Object spat=null;boolean changed=false,needsReload=false,common=false;String expected=null;int rc=0;
  try{
   if(args.length!=1||args[0].length()>32768||!args[0].matches("[A-Za-z0-9+/=]+"))throw new Exception("Expected bounded base64 edit batch");
   String mode=args[0].equals("on")||args[0].equals("off")?args[0]:"custom";
   String[] lines=mode.equals("custom")?new String(Base64.getDecoder().decode(args[0]),"UTF-8").trim().split("\n"):new String[0];
   if(mode.equals("custom")&&(lines.length<1||lines.length>128))throw new Exception("Invalid edit count");
   RuntimeControl.verify();before=nativeCall(Arrays.asList("status"),null);expected=identity(before);JSONArray records=before.getJSONArray("records");
   if(!mode.equals("custom")){List<String> generated=new ArrayList<>();for(int i=0;i<records.length();i++){JSONObject r=records.getJSONObject(i);String k=r.getString("key");if(mode.equals("on")&&(!r.getBoolean("is_flag")))continue;generated.add(r.getInt("profile")+"\t"+k+"\t"+(mode.equals("on")?0:r.getInt("original")));}lines=generated.toArray(new String[0]);}
   spat=RuntimeControl.spat();JSONObject active=RuntimeControl.state(spat);int profile=active.getInt("profile");boolean rendering=RuntimeControl.editableProfile(profile)&&active.getBoolean("enabled")&&active.getBoolean("available")&&active.getInt("output")>0;
   List<String> set=new ArrayList<>();set.add("set");Set<String> seen=new HashSet<>();JSONArray edits=new JSONArray();
   for(String line:lines){String[] f=line.split("\t",-1);if(f.length!=3||!f[0].matches("-1|[0-3]|8")||!f[1].matches("[a-z][a-z0-9-]{2,63}")||!f[2].matches("-?[0-9]{1,4}"))throw new Exception("Invalid edit record");int p=Integer.parseInt(f[0]),v=Integer.parseInt(f[2]);String k=f[1],id=p+":"+k;if(!seen.add(id))throw new Exception("Duplicate runtime target "+id);
    JSONObject found=null;for(int i=0;i<records.length();i++){JSONObject r=records.getJSONObject(i);if(r.getInt("profile")==p&&r.getString("key").equals(k)){found=r;break;}}
    if(found==null||v<found.getInt("minimum")||v>found.getInt("maximum"))throw new Exception("Unsupported target or value "+id);
    set.add(f[0]);set.add(k);set.add(String.valueOf(v));edits.put(new JSONObject().put("profile",p).put("key",k).put("dsp_key",found.getString("dsp_key")).put("value",v));needsReload|=rendering&&(p==profile||p==-1);common|=p==-1;
   }
   save("edit-before.json",before);String mark=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.ROOT).format(new Date());
   JSONObject applied=nativeCall(set,expected);changed=true;if(!expected.equals(identity(applied)))throw new Exception("Audio process changed");
   JSONObject now=RuntimeControl.state(spat);if(rendering&&(now.getInt("profile")!=profile||now.getInt("output")!=active.getInt("output")||!now.getBoolean("enabled"))){needsReload=false;throw new Exception("Route/profile changed before reload; table restoration only");}
   JSONObject reload=needsReload?reload(spat,common):new JSONObject().put("profile_reloaded",false).put("notice","Target profile is inactive; stored for its next activation");
   String log=needsReload?run(Arrays.asList("/system/bin/logcat","-b","main","-d","-v","threadtime","--pid="+before.getInt("pid"),"-T",mark),null):"";
   JSONObject values=new JSONObject();java.util.regex.Matcher m=java.util.regex.Pattern.compile("setParam\\(([a-z]{3,4}) = #1\\[(-?[0-9]+)\\]\\)").matcher(log);while(m.find())values.put(m.group(1),Integer.parseInt(m.group(2)));
   boolean verified=needsReload;for(int i=0;i<edits.length();i++){JSONObject e=edits.getJSONObject(i);if(e.getInt("profile")==profile||e.getInt("profile")==-1)verified&=values.has(e.getString("dsp_key"))&&values.getInt(e.getString("dsp_key"))==e.getInt("value");}
   out.put("ok",true).put("mode",mode).put("time",new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date())).put("table",applied).put("reload",reload).put("requested",edits).put("dsp_last_setters",values).put("dsp_setters_observed",verified).put("notice","GLOBAL 参数影响所有档位/输出；DDP 会强制 dvle=0。重载清除六项临时覆盖值。");
   save("neutral-last.json",out);new File(BASE+"edit-before.json").delete();changed=false;
  }catch(Throwable e){rc=4;out.put("ok",false).put("error",e.getClass().getSimpleName()+": "+e.getMessage());
   if(changed&&before!=null){try{out.put("rollback_table",nativeCall(snapshot(before.getJSONArray("records")),expected));if(needsReload&&spat!=null)out.put("rollback_reload",reload(spat,common));out.put("rollback_complete",true);}catch(Throwable recovery){out.put("rollback_complete",false).put("rollback_error",String.valueOf(recovery));}save("edit-recovery.json",out);}
  }
  return out;
 }
}
