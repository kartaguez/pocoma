#!/usr/bin/env python3
"""Run after a fresh COMMAND gate; uses precisely that gate's compiled classes/dependencies."""
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[4]
evidence = Path(__file__).resolve().parent
report = next(root.glob('app/infra-persistence-primary-jpa/target/surefire-reports/TEST-*WriteRevisionPostgresTest.xml'))
cp = next(p.get('value') for p in ET.parse(report).findall('./properties/property') if p.get('name') == 'java.class.path')
with tempfile.TemporaryDirectory(prefix='f2-close-') as output:
    subprocess.run(['javac', '--release', '21', '-cp', cp, '-d', output, str(evidence / 'ClosePlans.java')], check=True)
    subprocess.run(['java', '-cp', output + ':' + cp, 'com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.ClosePlans', str(evidence / 'Exact-close-plans.json')], check=True)
