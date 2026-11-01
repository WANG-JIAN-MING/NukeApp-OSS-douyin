#!/usr/bin/env python3
# Compare APK build targets with controlled cache and mutation states.
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
TARGETS = [':apps:module:assembleDebug', ':apps:module:assembleRelease',
           ':fixtures:host:assembleDebug', ':fixtures:host:assembleRelease']
if (ROOT / 'apps/patcher/build.gradle.kts').is_file():
    TARGETS += [':apps:patcher:assembleDebug', ':apps:patcher:assembleRelease']
OPTIONS = ['--offline', '--no-build-cache', '--no-configuration-cache', '--max-workers=1',
           '-Dorg.gradle.parallel=false', '--console=plain']


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def invoke(tasks, log):
    command = [str(ROOT / 'gradlew'), *tasks, *OPTIONS]
    begin = time.perf_counter()
    with log.open('w') as output:
        process = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    duration = time.perf_counter() - begin
    content = log.read_text()
    result = {'elapsed_seconds': round(duration, 3), 'exit_code': process.returncode,
              'command': command, 'log': str(log.relative_to(ROOT))}
    match = re.search(r'(\d+) actionable tasks: (.*)', content)
    if match:
        result['actionable_tasks'] = int(match.group(1))
        result['task_summary'] = match.group(2)
    if process.returncode:
        raise RuntimeError(f'Build failed: {log}\n{content[-5000:]}')
    return result


def locate(*candidates):
    for name in candidates:
        path = ROOT / name
        if path.exists():
            return path
    raise RuntimeError(f'Missing source candidates: {candidates}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--label', choices=['before', 'after'], required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/build-benchmark')
    parser.add_argument('--runs', type=int, default=3)
    args = parser.parse_args()
    if args.runs < 1:
        parser.error('--runs must be positive')
    destination = args.output.resolve() / args.label
    destination.mkdir(parents=True, exist_ok=True)
    ui = locate('ui/settings/src/main/kotlin/io/github/nuke/oss/ui/settings/ProjectStatusView.kt',
                'apps/module/src/main/kotlin/io/github/nuke/oss/ui/settings/ProjectStatusView.kt',
                'apps/module/src/main/kotlin/io/github/nuke/oss/ui/manager/ManagerScreen.kt')
    hook = locate('fixtures/adapter/src/main/kotlin/io/github/nuke/oss/fixture/adapter/FixtureHostAdapter.kt',
                  'apps/module/src/main/kotlin/io/github/nuke/oss/fixture/adapter/FixtureHostAdapter.kt',
                  'apps/module/src/main/kotlin/io/github/nuke/oss/app/fixture/FixtureHostAdapter.kt')
    originals = {path: path.read_bytes() for path in (ui, hook)}
    sources = {str(path.relative_to(ROOT)): hashlib.sha256(data).hexdigest() for path, data in originals.items()}
    native = {abi: digest(ROOT / f'build/native/{abi}/libnuke_zygisk.so')
              for abi in ('arm64-v8a',)}
    record = {'label': args.label, 'runs': args.runs, 'root': str(ROOT),
              'modules': [name for group in re.findall(r'\binclude\(([^)]*)\)', (ROOT/'settings.gradle.kts').read_text())
                          for name in re.findall(r'"(:[^"]+)"', group)],
              'targets': TARGETS, 'options': OPTIONS, 'system': platform.platform(),
              'java_version': subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True),
              'gradle_properties': (ROOT/'gradle.properties').read_text(),
              'gradle_wrapper': (ROOT/'gradle/wrapper/gradle-wrapper.properties').read_text(),
              'source_sha256': sources, 'native_sha256_before': native,
              'conditions': 'Same workspace path, warm Gradle/Kotlin daemons, cached dependencies and native outputs; no build/configuration cache; one worker.',
              'cases': {}, 'setup': []}
    report_path = destination / 'results.json'

    def save():
        report_path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')

    with open('/tmp/nukeapp-gradle.lock', 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            # Warm script/configuration compilation and the daemons before recording samples.
            record['setup'].append(invoke(TARGETS, destination/'warmup.log'))
            save()
            for case in ('clean_outputs', 'no_change', 'ui_incremental', 'hook_incremental'):
                samples = []
                for index in range(args.runs):
                    if case == 'clean_outputs':
                        record['setup'].append(invoke(['clean'], destination/f'clean-{index+1}-setup.log'))
                    elif case.endswith('_incremental'):
                        path = ui if case == 'ui_incremental' else hook
                        original = originals[path]
                        ui_change = ((b'widthIn(max = 720.dp)', b'widthIn(max = 721.dp)')
                                     if ui.name == 'ManagerScreen.kt' else (b'textSize = 28f', b'textSize = 28.01f'))
                        old, new = (ui_change if case == 'ui_incremental'
                                    else (b'it.setArgument(0, "NukeApp")', b'it.setArgument(0, "NukeAppBench")'))
                        if original.count(old) != 1:
                            raise RuntimeError(f'Expected one benchmark mutation in {path}')
                        path.write_bytes(original.replace(old, new, 1))
                    result = invoke(TARGETS, destination/f'{case}-{index+1}.log')
                    samples.append(result)
                    record['cases'][case] = {'samples': samples,
                        'median_seconds': round(statistics.median(row['elapsed_seconds'] for row in samples), 3)}
                    save()
                    print(f"{args.label} {case} {index+1}/{args.runs}: {result['elapsed_seconds']}s", flush=True)
                    if case.endswith('_incremental'):
                        path.write_bytes(originals[path])
                        record['setup'].append(invoke(TARGETS, destination/f'{case}-{index+1}-restore.log'))
                save()
        finally:
            for path, original in originals.items():
                if path.read_bytes() != original:
                    path.write_bytes(original)
            record['sources_restored'] = all(path.read_bytes() == value for path,value in originals.items())
            record['native_sha256_after'] = {abi: digest(ROOT / f'build/native/{abi}/libnuke_zygisk.so') for abi in native}
            record['native_unchanged'] = record['native_sha256_after'] == native
            save()
    print(f'Results: {report_path}', flush=True)


if __name__ == '__main__':
    main()
