package io.github.dcl;
import java.lang.reflect.*;
import java.nio.*;
import java.io.*;
import java.security.*;
import java.util.*;
import org.json.*;

/** Bounded profile reload of the existing system Spatializer; no effect construction. */
public final class RuntimeControl {
 static final String FP="samsung/dm1qzcx/dm1q:16/BP4A.251205.006/S9110ZCS8FZF5:user/release-keys";
 static void verify() throws Exception {
  if(!FP.equals(Class.forName("android.os.Build").getField("FINGERPRINT").get(null)))throw new Exception("Unsupported firmware");
  String[][] files={{"/system/lib64/libswspatializer_legacy.so","ea8f708f531f9a996d622df545eec51d23be8adcab63635e4bea2a117c3e0135"},{"/system/lib64/libspatializerparamstorage.so","649c9f11ce3d2682c877a20272dd924d65c83e5500100596880d4ccefe89a1ca"}};
  for(String[] f:files){MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f[0])){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}StringBuilder h=new StringBuilder();for(byte b:md.digest())h.append(String.format(Locale.ROOT,"%02x",b&255));if(!f[1].equals(h.toString()))throw new Exception("Unsupported library "+f[0]);}
 }
 static Object cachedSpatializer;
 static android.content.Context systemContext;
 static boolean editableProfile(int profile){return profile==0||profile==1||profile==2||profile==3||profile==8;}
 static synchronized Object spat() throws Exception {
  if(cachedSpatializer!=null)return cachedSpatializer;
  Class<?> loop=Class.forName("android.os.Looper");if(loop.getMethod("myLooper").invoke(null)==null)loop.getMethod("prepareMainLooper").invoke(null);
  Class<?> at=Class.forName("android.app.ActivityThread");Object t=at.getMethod("systemMain").invoke(null);Object ctx=at.getMethod("getSystemContext").invoke(t);
  systemContext=(android.content.Context)ctx;Object am=systemContext.getSystemService("audio");cachedSpatializer=Class.forName("android.media.AudioManager").getMethod("getSpatializer").invoke(am);return cachedSpatializer;
 }
 static int get(Object s,int id)throws Exception{Method m=s.getClass().getMethod("getEffectParameter",int.class,byte[].class);byte[] a=new byte[4],b=new byte[4];Arrays.fill(a,(byte)0x5a);Arrays.fill(b,(byte)0xa5);m.invoke(s,id,a);m.invoke(s,id,b);if(!Arrays.equals(a,b))throw new Exception("Unwritten parameter readback");return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).getInt();}
 static void profile(Object s,int value)throws Exception{s.getClass().getMethod("setEffectParameter",int.class,byte[].class).invoke(s,0x100,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());if(get(s,0x100)!=value)throw new Exception("Profile readback mismatch");}
 static JSONObject state(Object s)throws Exception{
  boolean enabled=(Boolean)s.getClass().getMethod("isEnabled").invoke(s),available=(Boolean)s.getClass().getMethod("isAvailable").invoke(s);
  JSONObject out=new JSONObject().put("enabled",enabled).put("available",available).put("output",0).put("profile",-1);
  if(!enabled||!available)return out;
  try{int output=(Integer)s.getClass().getMethod("getOutput").invoke(s);out.put("output",output);if(output>0)out.put("profile",get(s,0x100));}
  catch(InvocationTargetException e){Throwable c=e.getCause();if(!(c instanceof IllegalStateException))throw e;out.put("enabled",false);}
  return out;
 }
 public static void main(String[] args)throws Exception {
  JSONObject out=new JSONObject().put("effect_created",false).put("audio_written",false);int rc=0;
  try{
   if(args.length!=1||(!args[0].equals("check")&&!args[0].equals("reload")))throw new Exception("Expected check or reload");verify();Object s=spat();JSONObject before=state(s);out.put("before",before);
   int original=before.getInt("profile");if(!editableProfile(original))throw new Exception("Unsupported main profile");
   if(!before.getBoolean("enabled")||!before.getBoolean("available")||before.getInt("output")<=0)throw new Exception("Spatializer is not active");
   if(args[0].equals("reload")){
    // A real profile change is required: the native same-profile setter returns early.
    // Both writes use the current system instance; no enable/render/headtracking calls.
    int temporary=original==3?2:3;Exception failure=null;out.put("temporary_profile",temporary).put("audio_written",true);
    try{profile(s,temporary);}catch(Exception e){failure=e;}
    try{profile(s,original);}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
    if(failure!=null)throw failure;
    JSONObject after=state(s);out.put("after",after);if(!after.getBoolean("enabled")||after.getInt("output")!=before.getInt("output")||after.getInt("profile")!=original)throw new Exception("Spatializer route/state changed during reload");
    out.put("profile_reloaded",true).put("specific_overrides_reset",true);
   }
   out.put("ok",true);
  }catch(Throwable e){if(e instanceof InvocationTargetException&&e.getCause()!=null)e=e.getCause();out.put("ok",false).put("error",e.getClass().getSimpleName()+": "+e.getMessage());rc=4;}
  System.out.println(out.toString());if(rc!=0)System.exit(rc);
 }
}
