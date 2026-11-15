#!/usr/bin/env python3
"""Bootstrap guard: keep this file parseable by older Python 3 interpreters.

The supported minimum comes from the distribution contract (formerly only in
Quickstart), not from the version installed on the developer's workstation.
"""
import sys
import json
import os


def main():
    contract_path = os.path.join(os.path.dirname(__file__), 'toolchain.json')
    try:
        with open(contract_path) as stream:
            minimum = json.load(stream)['pythonMinimum']
        if not isinstance(minimum, list) or len(minimum) != 2 or not all(type(v) is int and v >= 0 for v in minimum):
            raise ValueError('Invalid Python contract')
        required = '.'.join(str(value) for value in minimum)
    except (OSError, ValueError, KeyError, TypeError):
        sys.stderr.write('ERROR — Python contract unavailable\nRequired: scripts/local/toolchain.json\n'
                         'Action: restore the distribution files and rerun ./pocoma-local.\n')
        return 2
    if tuple(sys.version_info[:2]) < tuple(minimum):
        sys.stderr.write('ERROR — Python incompatible\nRequired: Python >= ' + required + '\n'
                         'Detected: Python ' + '.'.join(str(v) for v in sys.version_info[:3]) + '\n'
                         'Action: install a compatible Python version and rerun ./pocoma-local.\n')
        return 2
    try:
        import fcntl
    except ImportError:
        sys.stderr.write('ERROR — System incompatible\nRequired: POSIX Python with fcntl\n'
                         'Action: use the documented macOS host; Windows native is unsupported.\n')
        return 2
    import runtime
    return runtime.main()


if __name__ == '__main__':
    sys.exit(main())
