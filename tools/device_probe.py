from pathlib import Path
import subprocess, shlex, json, sys

def remote(command, root=True):
    if root: command='su -c '+shlex.quote(command)
    p=subprocess.run(['adb','shell',command],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
    if p.returncode: raise RuntimeError(p.stderr.strip() or p.stdout.strip())
    return p.stdout

def rpc(action, args=None, root=True):
    command='CLASSPATH=/data/local/tmp/dcl-probe.dex app_process /system/bin DeviceProbe '+shlex.quote(action)
    if args is not None: command+=' '+shlex.quote(json.dumps(args,separators=(',',':')))
    value=remote(command,root)
    return json.loads(value.strip().splitlines()[-1])

if __name__=='__main__':
    action=sys.argv[1] if len(sys.argv)>1 else 'status'
    args=json.loads(sys.argv[2]) if len(sys.argv)>2 else None
    print(json.dumps(rpc(action,args),ensure_ascii=False,indent=2))
