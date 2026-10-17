from pathlib import Path
import subprocess, sys, json, xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]

def adb(*args):
    return subprocess.run(['adb',*args],capture_output=True,check=True,timeout=25).stdout

if len(sys.argv)>1 and sys.argv[1]=='screenshot':
    name=sys.argv[2] if len(sys.argv)>2 else 'phone'
    data=adb('exec-out','screencap','-p')
    if not data.startswith(b'\x89PNG'): raise ValueError('Invalid screenshot')
    dest=ROOT/'build'/f'{name}.png'; dest.write_bytes(data)
    print(dest)
else:
    adb('shell','uiautomator','dump','/data/local/tmp/dcl-ui.xml')
    data=adb('shell','cat','/data/local/tmp/dcl-ui.xml')
    root=ET.fromstring(data)
    print(json.dumps([{k:n.get(k) for k in ['text','content-desc','resource-id','class','bounds','enabled','checked','clickable']} for n in root.iter('node') if n.get('text') or n.get('content-desc')],ensure_ascii=False,indent=2))
