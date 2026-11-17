from pathlib import Path
import subprocess,sys
p=Path(__file__).parent
cp=str(p)+':'+(p/'classpath.txt').read_text()
mode=sys.argv[1]
r=subprocess.run(['java','-cp',cp,'IndependentAudit',*sys.argv[1:]],capture_output=True,text=True)
name=mode+('-'+sys.argv[2] if len(sys.argv)>2 else '')+'-final.log'
(p/name).write_text(r.stdout+r.stderr)
print('mode',mode,'exit',r.returncode)
print('\n'.join(x for x in (r.stdout+r.stderr).splitlines() if x.startswith('AUDIT') or 'Exception in thread' in x or 'Caused by:' in x))
sys.exit(r.returncode)
