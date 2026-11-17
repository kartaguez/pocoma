from pathlib import Path
import subprocess,sys,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[4]
xml=root/'app/infra-persistence-primary-jpa/target/surefire-reports/TEST-com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.CommandExecutionPostgresTest.xml'
props={x.get('name'):x.get('value') for x in ET.parse(xml).findall('.//property')}
cp=props['java.class.path'];name=sys.argv[1];label=sys.argv[2]
source=root/'docs/testing/evidence/L2-I2'/(name+'.java');classes=Path('/private/tmp/i2-supplement-classes');classes.mkdir(exist_ok=True)
with (root/'docs/testing/evidence/L2-I2'/(label+'.log')).open('x') as log:
 subprocess.run(['javac','--release','21','-cp',cp,'-d',str(classes),str(source)],check=True,stdout=log,stderr=subprocess.STDOUT)
 code=subprocess.run(['java','-ea','-cp',str(classes)+':'+cp,'com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.'+name],cwd=root/'app/infra-persistence-primary-jpa',stdout=log,stderr=subprocess.STDOUT).returncode
print(label,'exit=',code)
sys.exit(code)
