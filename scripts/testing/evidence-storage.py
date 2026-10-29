#!/usr/bin/env python3
"""Verify retained bytes or restore a historical gzip envelope; never run tests.

The inventory is a storage receipt, not a source of application PASS results.
Restoration writes only to a new, explicitly supplied destination.
"""
import argparse
import base64
import hashlib
import json
import lzma
from pathlib import Path
import sys
import subprocess
import zlib

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / 'docs/testing/Evidence_Inventory.json'
RETENTION = ROOT / 'docs/testing/Evidence_Retention.json'


def chunks(stream):
    while data := stream.read(1024 * 1024):
        yield data


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def gzip_parts(path):
    """Preserve the complete original header, including optional gzip fields."""
    with path.open('rb') as stream:
        header = bytearray(stream.read(10))
        if len(header) != 10 or header[:3] != b'\x1f\x8b\x08' or header[3] & 0xe0:
            raise ValueError('Unsupported gzip header')
        flags = header[3]
        if flags & 4:
            length = stream.read(2)
            header.extend(length)
            header.extend(stream.read(int.from_bytes(length, 'little')))
        for flag in (8, 16):
            if flags & flag:
                while True:
                    value = stream.read(1)
                    if not value:
                        raise ValueError('Truncated gzip header')
                    header.extend(value)
                    if value == b'\0':
                        break
        if flags & 2:
            header.extend(stream.read(2))
        stream.seek(-8, 2)
        footer = stream.read(8)
    return bytes(header), footer


def historical_gzip(entry, output=None):
    """Stream the exact envelope; verify both TAR and historical gzip digests."""
    encoding = entry['replacement']
    path = ROOT / encoding['path']
    if digest(path) != encoding['sha256']:
        raise ValueError('Changed XZ: ' + str(path))
    header = base64.b64decode(encoding['gzip_header_b64'], validate=True)
    footer = base64.b64decode(encoding['gzip_footer_b64'], validate=True)
    compressor = zlib.compressobj(encoding['gzip_level'], zlib.DEFLATED, -15)
    tar_hash, envelope_hash = hashlib.sha256(), hashlib.sha256()
    size = 0

    def emit(data):
        envelope_hash.update(data)
        if output is not None:
            output.write(data)

    emit(header)
    with lzma.open(path, 'rb') as stream:
        for data in chunks(stream):
            tar_hash.update(data)
            size += len(data)
            emit(compressor.compress(data))
    emit(compressor.flush())
    emit(footer)
    if size != encoding['tar_bytes'] or tar_hash.hexdigest() != encoding['tar_sha256']:
        raise ValueError('Changed TAR stream: ' + entry['path'])
    if envelope_hash.hexdigest() != entry['sha256']:
        raise ValueError('Cannot reconstruct original gzip exactly: ' + entry['path'])


def restore(entry, destination):
    # Exclusive creation, including symlinks: existing work is never overwritten.
    with destination.open('xb') as output:
        try:
            historical_gzip(entry, output)
        except BaseException:
            output.close()
            destination.unlink()
            raise


def historical_bytes(entry, output=None, root=None):
    """Read an immutable Git blob; verify exact bytes, never regenerate evidence."""
    root = root or ROOT
    locator = entry['commit'] + ':' + entry.get('historical_path', entry['path'])
    process = subprocess.Popen(['git', 'show', locator], cwd=root, stdout=subprocess.PIPE)
    digest = hashlib.sha256()
    size = 0
    try:
        for data in chunks(process.stdout):
            digest.update(data)
            size += len(data)
            if output is not None:
                output.write(data)
    finally:
        process.stdout.close()
        returncode = process.wait()
    if returncode or size != entry['bytes'] or digest.hexdigest() != entry['sha256']:
        raise ValueError('Historical Git bytes unavailable or changed: ' + locator)


def restore_historical(entry, destination, root=None):
    with destination.open('xb') as output:
        try:
            historical_bytes(entry, output, root)
        except BaseException:
            output.close()
            destination.unlink()
            raise


def retained_sources(retention):
    historical = {row['path']: row['historical'] for row in retention['artifacts']
                  if row['category'] == 'B'}
    historical.update({row['path']: row for row in retention['historical_gzip_aliases']})
    return historical


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('check', 'resolve', 'restore'))
    parser.add_argument('path', nargs='?')
    parser.add_argument('--destination', type=Path)
    args = parser.parse_args()
    inventory = json.loads(INVENTORY.read_text())
    retention = json.loads(RETENTION.read_text()) if RETENTION.exists() else {}
    historical = retained_sources(retention) if retention else {}
    updates = retention.get('head_updates', {})
    entries = inventory['artifacts']
    if args.operation == 'check':
        checked = transcoded = git_blobs = 0
        for entry in entries:
            if entry['path'] in historical:
                historical_bytes(historical[entry['path']])
                git_blobs += 1
            elif 'replacement' in entry:
                historical_gzip(entry)
                transcoded += 1
            elif digest(ROOT / entry['path']) != updates.get(entry['path'], entry.get('current_sha256', entry['sha256'])):
                raise ValueError('Changed retained artifact: ' + entry['path'])
            checked += 1
        # Includes the XZ identity independently of its original gzip alias.
        for row in retention.get('artifacts', []):
            if row['category'] == 'B':
                historical_bytes(row['historical'])
            elif digest(ROOT / row['path']) != updates.get(row['path'], row['sha256']):
                raise ValueError('Changed HEAD artifact: ' + row['path'])
        print(f'OK: {checked} baseline artifacts; {git_blobs} historical Git sources; '
              f'{transcoded} local gzip reconstructions; all retention rows verified; '
              'no application certification')
        return
    if not args.path:
        parser.error('path is required')
    requested = Path(args.path)
    if requested.is_absolute():
        requested = requested.resolve().relative_to(ROOT.resolve())
    path = requested.as_posix()
    entry = next((e for e in entries if e['path'] == path), None)
    if path in historical:
        source = historical[path]
        if args.operation == 'resolve':
            historical_bytes(source)
            print('git:' + source['commit'] + ':' + source.get('historical_path', source['path']))
        else:
            if not args.destination:
                parser.error('restore requires --destination (new file)')
            restore_historical(source, args.destination)
            print(f'OK: {args.destination}; historical SHA-256 {source["sha256"]}')
    elif entry is None:
        parser.error('path is not in the inventories')
    elif args.operation == 'resolve':
        print(ROOT / entry.get('replacement', entry)['path'])
    else:
        if not args.destination or 'replacement' not in entry:
            parser.error('restore requires --destination and a historical/transcoded archive')
        restore(entry, args.destination)
        print(f'OK: {args.destination}; historical SHA-256 {entry["sha256"]}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, lzma.LZMAError) as error:
        sys.exit(str(error))
