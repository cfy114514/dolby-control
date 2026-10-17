package io.github.dcl;
import java.io.*;
import java.nio.channels.*;
/** Serializes edits, manual changes and the automatic rule in this module. */
final class ProfileLock implements AutoCloseable {
 private final RandomAccessFile file;private final FileChannel channel;private final FileLock lock;
 ProfileLock()throws Exception{
  File path=new File("/data/adb/dolby_control_app/profile-operation.lock");
  if(!path.getCanonicalPath().equals(path.getAbsolutePath()))throw new IOException("Unsafe operation lock");
  file=new RandomAccessFile(path,"rw");channel=file.getChannel();FileLock acquired=channel.tryLock();
  if(acquired==null){channel.close();file.close();throw new IOException("Audio operation busy; retry shortly");}lock=acquired;
 }
 public void close()throws IOException{try{lock.release();}finally{try{channel.close();}finally{file.close();}}}
}
