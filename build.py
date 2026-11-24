#!/usr/bin/env python3
"""Offline APK builder using a JDK and official Android SDK build tools.

python3 build.py --sdk /path/to/sdk --java-home /path/to/jdk \
    --signing /private/signing --output /path/novelstudio-1.4.1.apk
Keep the signing directory private; it is required to install later updates.
"""
import argparse
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import zipfile


def version_key(path):
    return tuple(int(value) for value in re.findall(r"\d+", str(path.parent.name)))


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', required=True)
parser.add_argument('--java-home')
parser.add_argument('--signing', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent
sdk = Path(args.sdk).resolve()
out = Path(args.output).resolve()
signing = Path(args.signing).resolve()
build = root / 'build'
if build.is_symlink():
    raise SystemExit('Refusing a symlink build directory')
shutil.rmtree(build, ignore_errors=True)
for directory in (build / 'generated', build / 'classes', build / 'dex', signing, out.parent):
    directory.mkdir(parents=True, exist_ok=True)
os.chmod(signing, 0o700)
platforms = sorted((sdk / 'platforms').glob('*/android.jar'), key=version_key)
build_tools = sorted((sdk / 'build-tools').glob('*/aapt2'), key=version_key)
if not platforms or not build_tools:
    raise SystemExit('Install Android platform 36 and build-tools 35 or newer first')
android_jar, bt = platforms[-1], build_tools[-1].parent
env = os.environ.copy()
env.setdefault('JAVA_TOOL_OPTIONS', '-Xmx512m')
if args.java_home:
    env['JAVA_HOME'] = str(Path(args.java_home).resolve())
    env['PATH'] = str(Path(args.java_home).resolve() / 'bin') + os.pathsep + env['PATH']


def run(*command):
    subprocess.run([str(arg) for arg in command], check=True, cwd=root, env=env)


main = root / 'app/src/main'
run(bt / 'aapt2', 'compile', '--dir', main / 'res', '-o', build / 'res.zip')
run(bt / 'aapt2', 'link', '-o', build / 'resources.apk', '-I', android_jar,
    '--manifest', main / 'AndroidManifest.xml', '--java', build / 'generated',
    '--min-sdk-version', '26', '--target-sdk-version', '36', '-A', main / 'assets', build / 'res.zip')
sources = sorted((main / 'java').rglob('*.java')) + sorted((build / 'generated').rglob('*.java'))
run('javac', '-source', '8', '-target', '8', '-encoding', 'UTF-8',
    '-classpath', android_jar, '-d', build / 'classes', *sources)
run('jar', '--create', '--file', build / 'classes.jar', '-C', build / 'classes', '.')
run(bt / 'd8', '--lib', android_jar, '--min-api', '26', '--output', build / 'dex', build / 'classes.jar')
shutil.copyfile(build / 'resources.apk', build / 'unsigned.apk')
with zipfile.ZipFile(build / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as archive:
    for dex in (build / 'dex').glob('*.dex'):
        archive.write(dex, dex.name)
run(bt / 'zipalign', '-p', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk')
password = signing / 'password.txt'
keystore = signing / 'novelstudio.p12'
if not keystore.exists():
    if not password.exists():
        password.write_text(secrets.token_urlsafe(36), encoding='utf-8')
        os.chmod(password, 0o600)
    run('keytool', '-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12',
        '-storepass:file', password, '-keypass:file', password, '-alias', 'novelstudio',
        '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
        '-dname', 'CN=NovelStudio Personal,OU=Personal Apps,O=Personal,C=JP')
    os.chmod(keystore, 0o600)
run(bt / 'apksigner', 'sign', '--ks', keystore, '--ks-key-alias', 'novelstudio',
    '--ks-pass', 'file:' + str(password), '--out', out, build / 'aligned.apk')
run(bt / 'apksigner', 'verify', '--verbose', out)
print(f'Built {out.name}: {out.stat().st_size:,} bytes')
