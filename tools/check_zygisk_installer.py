#!/usr/bin/env python3
# Exercise customize.sh in a temporary, unprivileged installer harness.
import os
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'apps/module/src/main/resources'
SCRIPT = RESOURCES / 'customize.sh'


def check(arch='arm64', api='27', boot='true', extra_dex=False, omit_native=False, omit_primary=False):
    with tempfile.TemporaryDirectory(prefix='nukeapp-installer-') as temporary:
        directory = Path(temporary)
        archive = directory / 'module.zip'
        module_properties = (RESOURCES / 'module.prop').read_bytes()
        with ZipFile(archive, 'w') as output:
            output.writestr('module.prop', module_properties)
            if not omit_primary:
                output.writestr('classes.dex', b'dex\npayload')
            if extra_dex:
                output.writestr('classes2.dex', b'dex\nunsupported')
            if not omit_native:
                output.writestr('lib/arm64-v8a/libnuke_zygisk.so', b'arm64-library')
        destination = directory / 'installed'
        destination.mkdir()
        harness = '\n'.join([
            'abort() { echo "$1" >&2; exit 1; }',
            'ui_print() { echo "$1"; }',
            'set_perm_recursive() { :; }',
            '. "$NUKE_INSTALLER"',
        ])
        environment = {**os.environ, 'ARCH': arch, 'API': api, 'BOOTMODE': boot,
                       'ZIPFILE': str(archive), 'MODPATH': str(destination), 'NUKE_INSTALLER': str(SCRIPT)}
        result = subprocess.run(['sh', '-c', harness], env=environment, capture_output=True, text=True)
        expected = arch == 'arm64' and int(api) >= 27 and boot == 'true' and not omit_primary and not omit_native
        assert (result.returncode == 0) == expected, (arch, api, boot, result)
        if expected:
            assert (destination / 'payload/classes.dex').read_bytes() == b'dex\npayload'
            assert (destination / 'zygisk' / 'arm64-v8a.so').read_bytes() == b'arm64-library'
            assert (destination / 'module.prop').read_bytes() == module_properties
            if extra_dex:
                assert (destination / 'payload/classes2.dex').read_bytes() == b'dex\nunsupported'
            assert len(list((destination / 'zygisk').iterdir())) == 1


if __name__ == '__main__':
    subprocess.run(['sh', '-n', str(SCRIPT)], check=True)
    subprocess.run(['sh', '-n', str(SCRIPT.parent / 'META-INF/com/google/android/update-binary')], check=True)
    check()
    check(arch='x64')
    check(arch='arm')
    check(api='26')
    check(boot='false')
    check(extra_dex=True)
    check(omit_native=True)
    check(extra_dex=True, omit_primary=True)
    print('Zygisk installer harness: 8 scenarios PASS (no Root/device execution)')
