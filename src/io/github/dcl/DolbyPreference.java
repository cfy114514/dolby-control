package io.github.dcl;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.lang.reflect.*;
final class DolbyPreference {
 static Context context;
 static int mode()throws Exception{
  String authority="com.sec.android.app.soundalive.compatibility.SAContentProvider";IBinder token=new Binder();
  Class<?> iam=Class.forName("android.app.IActivityManager");Object am=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
  Object holder=iam.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class).invoke(am,authority,0,token,"io.github.dcl.AutoMusic");
  if(holder==null)throw new Exception("Dolby provider unavailable");
  try{
   Object provider=holder.getClass().getField("provider").get(holder);Class<?> attribution=Class.forName("android.content.AttributionSource");
   Object source=attribution.getMethod("myAttributionSource").invoke(null);
   Method query=Class.forName("android.content.IContentProvider").getMethod("query",attribution,Uri.class,String[].class,Bundle.class,Class.forName("android.os.ICancellationSignal"));
   try(Cursor c=(Cursor)query.invoke(provider,source,Uri.parse("content://"+authority),new String[]{"DOLBY_ATMOS_LEVEL"},null,null)){
   if(c==null||!c.moveToFirst())throw new Exception("Dolby preference unavailable");int i=c.getColumnIndex("Result");if(i<0)throw new Exception("Unexpected Dolby provider layout");int mode=Integer.parseInt(c.getString(i));if(mode!=0&&mode!=1&&mode!=2&&mode!=3&&mode!=5)throw new Exception("Unsupported Dolby preference");return mode;
   }
  }finally{iam.getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class).invoke(am,authority,token,0);}
 }
}
