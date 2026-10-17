from device_probe import rpc,remote
from pathlib import Path
import json,time,subprocess

OUT=Path(__file__).resolve().parents[1]/'build'
before=rpc('settings'); rows=[]
entry_enabled=before['enabled'];entry_profile=before['selected_profile']
assert entry_profile in (0,1,2,3,8)

def tap(x,y):
    focus=remote('dumpsys window | grep mCurrentFocus')
    assert 'com.sec.android.app.soundalive.activity.DolbyAtmosActivity' in focus, 'Phone UI changed during test'
    remote(f'input tap {x} {y}')

def sample(label,goal,enabled):
    for _ in range(10):
        s=rpc('status')
        if s['selected_profile']==goal and s['enabled']==enabled and (not enabled or s['profile']==goal):break
        time.sleep(.25)
    assert s['selected_profile']==goal and s['enabled']==enabled,(label,s)
    if enabled:assert s['active'] and s['profile']==goal,(label,s)
    rows.append({'event':label,'status':s});print(json.dumps(rows[-1],ensure_ascii=False),flush=True)

remote('am start -n com.sec.android.app.soundalive/.activity.DolbyAtmosActivity')
if not before['enabled']:tap(930,400)
tap(500,1750)
sample('spatial_initial',8,True)
tap(930,400)
sample('off_initial',8,False)
rpc('reassert');remote('input keyevent 127');remote('input keyevent 126')
sample('off_stays_off_after_playback_and_reassert',8,False)
tap(930,400)
sample('native_enable_restores_manual_spatial',8,True)
tap(500,840)
sample('native_auto_releases_manual_lock',0,True)
remote('input keyevent 127');remote('input keyevent 126');remote('input keyevent 87')
sample('auto_not_forced_to_music_after_playback',0,True)
tap(500,1750)
sample('native_spatial_again',8,True)
saved=rpc('settings')
rows.append({'event':'before_restart','settings':saved})
# Package replacement is a real protected system event and exercises the same
# receiver/activation path as boot, without synthesizing a protected broadcast.
apk=OUT.parents[2]/'outputs/Dolby_Control_v0.6.1.apk'
subprocess.run(['adb','install','-r',str(apk)],check=True,capture_output=True)
for _ in range(20):
    try:
        s=rpc('settings')
        installed=remote('pm path io.github.dcl.app').strip().split('package:',1)[1]
        if s.get('ready') and s.get('profile')==8 and len(s.get('records',[]))==44 and s.get('apk')==installed:break
    except Exception:
        pass
    time.sleep(.4)
assert s['selected_profile']==8 and s['profile']==8
assert {(r['profile'],r['key']):r['current'] for r in s['records']}=={(r['profile'],r['key']):r['current'] for r in saved['records']}
assert s['apk']==remote('pm path io.github.dcl.app').strip().split('package:',1)[1]
rows.append({'event':'package_restart_restores_profile_and_all_settings','settings':s})
print('PASS: independent daemon restarted through protected package event; 44 settings retained',flush=True)
remote('am force-stop com.sec.android.app.soundalive')
remote('am start -n com.sec.android.app.soundalive/.activity.DolbyAtmosActivity')
sample('native_page_restart_keeps_spatial',8,True)
tap(500,{0:840,1:1055,2:1270,3:1540,8:1750}[entry_profile])
sample('restore_entry_profile',entry_profile,True)
if not entry_enabled:tap(930,400)
sample('restore_entry_dolby_state',entry_profile,entry_enabled)
(OUT/'lifecycle-live-evidence.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS: OFF, Auto release, saved Spatial, daemon restart, parameters, entry state restored')
