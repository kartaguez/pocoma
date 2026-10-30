from pathlib import Path
import subprocess,xml.etree.ElementTree as E
p=Path(__file__).parent
root=Path('/private/tmp/pocoma-l3-i3-audit-2b3567b/app')
xml=root/'runtime-command-consumption-worker/target/surefire-reports/TEST-com.kartaguez.pocoma.runtime.command.v3.V3IdentityRequestCompositionPostgresTest.xml'
cp=next(x.attrib['value'] for x in E.parse(xml).findall('.//property') if x.attrib['name']=='java.class.path')
(p/'classpath.txt').write_text(cp)
subprocess.run(['javac','-cp',cp,'-d',str(p),str(p/'IndependentAudit.java')],check=True)
print('External independent audit harness compiled from recorded runtime classpath')
