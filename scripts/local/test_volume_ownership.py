"""An attached volume is not implicitly owned, even if Compose would delete it."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import runtime as rt


class VolumeOwnership(unittest.TestCase):
    def test_anonymous_and_other_project_mounts_are_preserved(self):
        def inspect(*args):
            if args == ('inspect', 'container'):
                return [dict(Mounts=[dict(Type='volume', Name=name) for name in ('own', 'foreign', 'anonymous')])]
            name = args[-1]
            return [dict(Labels=None if name == 'anonymous' else {
                rt.LABEL:'owner', 'com.docker.compose.project':'project' if name == 'own' else 'another-project'})]
        with patch.object(rt, 'run', return_value='container'), patch.object(rt, 'docker_json', side_effect=inspect):
            self.assertEqual(['anonymous','foreign'], rt.unowned_attached_volumes(dict(project='project',owner='owner')))

    def test_only_explicit_owned_volume_is_removed_and_unowned_volume_survives(self):
        commands = []
        listed = False
        def command(args, **kwargs):
            nonlocal listed
            commands.append(args)
            if args[:4] == ['docker','volume','ls','-q'] and not listed:
                listed = True
                return 'project-data'
            return ''
        def inspect(*args):
            return [dict(Labels={rt.LABEL:'owner','com.docker.compose.project':'project'} if args[-1]=='project-data' else None)]
        with tempfile.TemporaryDirectory() as directory, patch.object(rt,'ownership'), \
             patch.object(rt,'unowned_attached_volumes',return_value=['unlabelled']), \
             patch.object(rt,'compose') as compose, patch.object(rt,'run',side_effect=command), \
             patch.object(rt,'docker_json',side_effect=inspect) as inspect_call:
            path = Path(directory)
            result = rt.remove_project(path,dict(project='project',owner='owner'),path,'down.log')
            self.assertEqual(['project-data'], result['removedOwnedVolumes'])
            self.assertEqual(['unlabelled'], result['preservedUnownedVolumes'])
            self.assertNotIn('--volumes', compose.call_args.args)
            self.assertEqual([['docker','volume','rm','project-data']], [c for c in commands if c[:3]==['docker','volume','rm']])
            self.assertIn((('volume','inspect','unlabelled'),{}),[(c.args,c.kwargs) for c in inspect_call.call_args_list])

    def test_changed_volume_owner_refuses_removal(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(rt,'ownership'), \
             patch.object(rt,'unowned_attached_volumes',return_value=[]), patch.object(rt,'compose'), \
             patch.object(rt,'run',return_value='project-data') as run, \
             patch.object(rt,'docker_json',return_value=[dict(Labels={rt.LABEL:'foreign','com.docker.compose.project':'project'})]):
            path = Path(directory)
            with self.assertRaisesRegex(rt.Refusal,'FOREIGN-VOLUME'):
                rt.remove_project(path,dict(project='project',owner='owner'),path,'down.log')
            self.assertFalse(any(c.args[0][:3]==['docker','volume','rm'] for c in run.call_args_list))


if __name__ == '__main__':
    unittest.main()
