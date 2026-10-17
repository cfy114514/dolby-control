"""Reproducible SDK-only build; no Gradle/plugin downloads or system configuration writes."""
from pathlib import Path
import hashlib, json, os, secrets, shutil, subprocess, zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('DCL_ANDROID_SDK', r'C:\Users\123\AppData\Local\Android\Sdk'))
JDK = Path(os.environ.get('DCL_JAVA', r'C:\Program Files\Android\Android Studio\jbr'))
BT = SDK / 'build-tools/36.0.0'
ANDROID = SDK / 'platforms/android-37.0/android.jar'
BUILD = ROOT / 'build'
BUILD.mkdir(exist_ok=True)
VERSION_NAME = '0.6.1'
VERSION_CODE = 601

def run(args):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        print(result.stdout)
        print(result.stderr)
        raise SystemExit(result.returncode)
    if result.stderr.strip(): print(result.stderr.strip()[:3000])
    return result.stdout

native_hash = hashlib.sha256((ROOT/'assets/backend/table-runtime').read_bytes()).hexdigest()
token_hash = hashlib.sha256()
for p in sorted((ROOT/'src').rglob('*.java')):
    if p.name != 'BuildIdentity.java': token_hash.update(p.relative_to(ROOT).as_posix().encode()+p.read_bytes())
token_hash.update(native_hash.encode())
token = token_hash.hexdigest()[:24]
(ROOT/'src/io/github/dcl/app/BuildIdentity.java').write_text(
    f'package io.github.dcl.app;\npublic final class BuildIdentity {{ public static final String TOKEN="{token}"; public static final String NATIVE_SHA256="{native_hash}"; }}\n', encoding='utf-8')
classes=BUILD/'classes'
if classes.exists(): shutil.rmtree(classes)
classes.mkdir()
api=ROOT/'vendor/xposed-api-101.jar'
classpath=os.pathsep.join(map(str,[ANDROID,api]))
sources=sorted((ROOT/'src').rglob('*.java'))
run([JDK/'bin/javac.exe','--release','17','-encoding','UTF-8','-cp',classpath,'-d',classes,*sources])
dex=BUILD/'dex'
if dex.exists(): shutil.rmtree(dex)
dex.mkdir()
run([JDK/'bin/java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--lib',ANDROID,'--classpath',api,'--min-api','35','--output',dex,*sorted(classes.rglob('*.class'))])
compiled=BUILD/'resources.zip'
run([BT/'aapt2.exe','compile','--dir',ROOT/'res','-o',compiled])
resources=BUILD/'resources.apk'
run([BT/'aapt2.exe','link','-I',ANDROID,'--manifest',ROOT/'AndroidManifest.xml','-o',resources,'-R',compiled,'--auto-add-overlay','--version-code',str(VERSION_CODE),'--version-name',VERSION_NAME,'-A',ROOT/'assets'])
unsigned=BUILD/'unsigned.apk'
shutil.copyfile(resources,unsigned)
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(dex.glob('*.dex')): z.write(p,p.name)
    for p in sorted((ROOT/'metadata').rglob('*')):
        if p.is_file(): z.write(p,p.relative_to(ROOT/'metadata').as_posix())
aligned=BUILD/'aligned.apk'
run([BT/'zipalign.exe','-f','4',unsigned,aligned])
private=ROOT/'private';private.mkdir(exist_ok=True)
key=private/'signing.p12';password_file=private/'signing-password.txt'
if not key.exists():
    if not password_file.exists():password_file.write_text(secrets.token_hex(24),encoding='ascii')
    run([JDK/'bin/keytool.exe','-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass:file',password_file,'-alias','dolby-control','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Dolby Control Local'])
output=ROOT.parents[1]/f'outputs/Dolby_Control_v{VERSION_NAME}.apk'
output.parent.mkdir(exist_ok=True)
run([JDK/'bin/java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','dolby-control','--ks-pass','file:'+str(password_file),'--out',output,aligned])
verification=run([JDK/'bin/java.exe','-jar',BT/'lib/apksigner.jar','verify','--verbose','--print-certs',output])
identity={'path':str(output),'bytes':output.stat().st_size,'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'build':token,'source_count':len(sources),'native_sha256':native_hash}
(BUILD/'identity.json').write_text(json.dumps(identity,ensure_ascii=False,indent=2),encoding='utf-8')
(BUILD/'signature.txt').write_text(verification,encoding='utf-8')
print(json.dumps(identity,ensure_ascii=False,indent=2))
