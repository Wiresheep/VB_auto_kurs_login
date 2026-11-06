#!/usr/bin/env python3
"""Install isolated Android build tools; verify downloads against publisher metadata."""
import gzip, hashlib, os, pathlib, shutil, subprocess, urllib.request, zipfile
root = pathlib.Path(os.environ.get('ANDROID_TOOLS_ROOT', '/workspace/.android-tools'))
cache = root / 'downloads'
cache.mkdir(parents=True, exist_ok=True)
def fetch(url, dest, digest=None, algorithm='sha256'):
    if not dest.exists():
        print('Download:', url, flush=True)
        with urllib.request.urlopen(url, timeout=120) as r, open(str(dest)+'.part','wb') as f:
            shutil.copyfileobj(r, f)
        pathlib.Path(str(dest)+'.part').replace(dest)
    if digest:
        actual = hashlib.new(algorithm, dest.read_bytes()).hexdigest()
        if actual != digest:
            raise RuntimeError('Checksum mismatch: '+str(dest))
    return dest
jdk = root / 'jdk/usr/lib/jvm/java-21-openjdk-amd64'
if not (jdk/'bin/javac').exists() or not (jdk/'lib/modules').exists():
    index = gzip.decompress(urllib.request.urlopen('https://archive.ubuntu.com/ubuntu/dists/noble-updates/main/binary-amd64/Packages.gz',timeout=60).read()).decode()
    for name in ['openjdk-21-jre-headless', 'openjdk-21-jdk-headless']:
        stanza = next(p for p in index.split('\n\n') if p.startswith('Package: '+name+'\n'))
        fields = dict(line.split(': ',1) for line in stanza.splitlines() if ': ' in line and not line.startswith(' '))
        package = fetch('https://archive.ubuntu.com/ubuntu/'+fields['Filename'],cache/pathlib.Path(fields['Filename']).name,fields['SHA256'])
        subprocess.run(['dpkg-deb','-x',str(package),str(root/'jdk')],check=True)
    # Debian's absolute configuration links refer to the host; use the extracted defaults.
    for path in (jdk/'conf').rglob('*'):
        if path.is_symlink() and str(path.readlink()).startswith('/etc/java-21-openjdk/'):
            source = root/'jdk'/str(path.readlink()).lstrip('/')
            if source.exists():
                path.unlink(); shutil.copy2(source,path)
    cacerts=jdk/'lib/security/cacerts'
    if cacerts.is_symlink() and not cacerts.exists():
        cacerts.unlink(); shutil.copy2('/etc/ssl/certs/java/cacerts',cacerts)
version = '8.11.1'
gradle = root / ('gradle-'+version)
if not (gradle/'bin/gradle').exists():
    z = fetch('https://services.gradle.org/distributions/gradle-'+version+'-bin.zip',cache/'gradle.zip','f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6')
    with zipfile.ZipFile(z) as f: f.extractall(root)
    (gradle/'bin/gradle').chmod(0o755)
sdk = root / 'sdk'
if not (sdk/'cmdline-tools/19.0/bin/sdkmanager').exists():
    z = fetch('https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip',cache/'commandline-tools.zip','5fdcc763663eefb86a5b8879697aa6088b041e70','sha1')
    temp = root/'sdk-extract'
    with zipfile.ZipFile(z) as f: f.extractall(temp)
    (sdk/'cmdline-tools').mkdir(parents=True,exist_ok=True)
    shutil.move(str(temp/'cmdline-tools'),str(sdk/'cmdline-tools/19.0'))
    for p in (sdk/'cmdline-tools/19.0/bin').iterdir(): p.chmod(0o755)
config = root/'env.sh'
config.write_text(f'export JAVA_HOME="{jdk}"\nexport ANDROID_HOME="{sdk}"\nexport ANDROID_SDK_ROOT="{sdk}"\nexport GRADLE_USER_HOME="{root}/gradle-cache"\nexport PATH="$JAVA_HOME/bin:{gradle}/bin:$ANDROID_HOME/platform-tools:$PATH"\n')
print('Tool environment:', config)
# Java does not read HTTPS_PROXY or SSL_CERT_FILE; retain verification via a local truststore.
import re, shlex, urllib.parse
proxy = urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',''))
options=[f'-Duser.home={root}/java-user']
(root/'java-user').mkdir(exist_ok=True)
(root/'android-user').mkdir(exist_ok=True)
if proxy.hostname:
    if proxy.username or proxy.password:
        raise RuntimeError('Authenticated proxy requires platform-supported Java proxy configuration')
    for scheme in ['http','https']:
        options += [f'-D{scheme}.proxyHost={proxy.hostname}',f'-D{scheme}.proxyPort={proxy.port or 80}']
certificate = os.environ.get('SSL_CERT_FILE')
if certificate:
    trust = root/'truststore.jks'
    if not trust.exists():
        shutil.copy2(jdk/'lib/security/cacerts',trust)
        pem = pathlib.Path(certificate).read_text()
        for n, block in enumerate(re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----',pem,re.S)):
            cert = cache/f'proxy-ca-{n}.pem'; cert.write_text(block+'\n')
            subprocess.run([str(jdk/'bin/keytool'),'-importcert','-noprompt','-trustcacerts',
                '-alias',f'cloud-proxy-{n}','-file',str(cert),'-keystore',str(trust),'-storepass','changeit'],check=True,
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    options += [f'-Djavax.net.ssl.trustStore={trust}']
with config.open('a') as f:
    f.write('export ANDROID_USER_HOME='+shlex.quote(str(root/'android-user'))+'\n')
    f.write('export JAVA_TOOL_OPTIONS='+shlex.quote(' '.join(options))+'\n')
