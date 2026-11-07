#!/usr/bin/env python3
"""CloudXtream V5.0.20: HTTPS to the CloudXtream server works on older Android and TV boxes.

Cloudflare serves certificates from Let's Encrypt (ISRG Root X1/X2) or Google Trust Services (GTS Root
R1/R4); Android 7.0 and older lack some of these roots, so activation failed with TLS_ERROR. The roots
are bundled (leque/certs, checked against their published SHA-256 fingerprints) and trusted in
addition to the system ones: network_security_config.xml on Android 7.0+, CxTls on Android 6."""
from pathlib import Path
import base64, hashlib, re, sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
HERE=Path(__file__).resolve().parent
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.20 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)
def add(path,text):
    if (ROOT/path).exists(): sys.exit("FAIL: V5.0.20 file already exists: "+path)
    files[path]=text

patch("gradle/libs.versions.toml",'versionCode = "50019"','versionCode = "50020"')
patch("gradle/libs.versions.toml",'versionName = "5.0.19"','versionName = "5.0.20"')

ROOTS={  # resource name: SHA-256 of the DER certificate (published by Let's Encrypt and Google)
 "cx_isrg_root_x1":"96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6",
 "cx_isrg_root_x2":"69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470",
 "cx_gts_root_r1":"D947432ABDE7B7FA90FC2E6B59101B1280E0E1C7E4E40FA3C6887FFF57A7F4CF",
 "cx_gts_root_r4":"349DFA4058C5E263123B398AE795573C4E1313C83FE68F93556CD5E8031B3C7D",
}
for name,fingerprint in ROOTS.items():
    pem=(HERE/"certs"/(name+".pem")).read_text(encoding="ascii")
    body=re.search(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",pem,re.S)
    if not body or hashlib.sha256(base64.b64decode("".join(body.group(1).split()))).hexdigest().upper()!=fingerprint:
        sys.exit("FAIL: V5.0.20 certificate fingerprint mismatch: "+name)
    add("app/src/main/res/raw/"+name+".pem",pem)

anchors="".join('            <certificates src="@raw/'+n+'" />\n' for n in ROOTS)
add("app/src/main/res/xml/network_security_config.xml",'''<?xml version="1.0" encoding="utf-8"?>
<!-- CloudXtream: same behaviour as before (cleartext allowed, system roots), plus the roots of the
     CAs Cloudflare uses, which Android 7.0 and older lack. -->
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
'''+anchors+'''        </trust-anchors>
    </base-config>
</network-security-config>
''')
patch("app/src/main/AndroidManifest.xml",'android:usesCleartextTraffic="true"',
      'android:usesCleartextTraffic="true"\n        android:networkSecurityConfig="@xml/network_security_config"')

# Android 6 ignores the config: our own HTTPS calls add the roots themselves.
add("app/src/main/java/com/lagradost/cloudstream3/activation/CxTls.kt",(HERE/"android"/"CxTls.kt").read_text(encoding="utf8"))
patch("app/src/main/java/com/lagradost/cloudstream3/plugins/LequePluginSync.kt",
      "        connection.connectTimeout = 8_000\n",
      "        connection.connectTimeout = 8_000\n        com.lagradost.cloudstream3.activation.CxTls.apply(connection)\n")
patch("app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt",
      "                connection.connectTimeout = 30_000\n",
      "                connection.connectTimeout = 30_000\n                com.lagradost.cloudstream3.activation.CxTls.apply(connection)\n")

for path,source in files.items():
    target=ROOT/path;target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(source,encoding="utf8")
print("PASS: V5.0.20 bundled CA roots for older Android",len(files),"files")
