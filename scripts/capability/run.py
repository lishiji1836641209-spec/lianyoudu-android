#!/usr/bin/env python3
"""Snapshot current project, verify baseline/mutations/recovery on an owned emulator."""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time

PROJECT = Path(__file__).resolve().parents[2]
TOOLS = PROJECT.parent / 'tools'
PKG = 'com.lianyoudu.app.mutationtest'
PORT = '5041'
SERIAL = 'emulator-5580'
AVD = 'CapabilityMerged'
SOURCE = Path('app/src/main/java/com/lianyoudu/app/MainActivity.java')
TEST = Path('app/src/androidTest/java/com/lianyoudu/app/CapabilityInstrumentation.java')
# Snapshot only the files used by the diagnostic build and mutation suite.
INPUTS = ('app/src', 'app/build.gradle', 'build.gradle', 'settings.gradle',
          'gradle.properties', 'scripts/capability/mutations.json')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def detected_fault(output, case, expected):
    # A previous OK line containing the same words must never count as the failure.
    marker = f'FAIL capability {case}:'
    if marker not in output or 'PASS capability' in output:
        return False
    failure = output.split(marker, 1)[1]
    return any(expected in line and 'java.lang.AssertionError:' in line
               for line in failure.splitlines()
               if line.startswith((' java.lang.AssertionError:', 'Caused by: java.lang.AssertionError:')))


def hashes(root):
    result = {}
    for name in INPUTS:
        item = root / name
        files = item.rglob('*') if item.is_dir() else [item]
        for path in files:
            if path.is_file() and path.name != '.DS_Store':
                result[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-dir', type=Path, required=True, help='New directory outside android-app; must not exist')
    parser.add_argument('--offline', action='store_true', help='Use previously cached Gradle dependencies')
    args = parser.parse_args()
    work = args.work_dir.resolve()
    require(not work.exists(), 'Refusing to reuse or overwrite an existing experiment directory')
    require(work != PROJECT and PROJECT not in work.parents and work not in PROJECT.parents,
            'Experiment must be outside the source project and cannot contain it')
    # Prevent accidentally occupying another task\'s server/console/transport.
    for port in (int(PORT), 5580, 5581):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', port))
    original = hashes(PROJECT)
    work.mkdir(parents=True)
    evidence = work / 'evidence'
    evidence.mkdir()
    copy = work / 'android-app'
    for relative in original:
        target = copy / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(PROJECT / relative, target)
    require(hashes(copy) == original and hashes(PROJECT) == original,
            'Source changed while being copied; stop and create a fresh snapshot')
    (evidence / 'source-sha256.json').write_text(json.dumps(original, indent=2))
    (evidence / 'environment.json').write_text(json.dumps({
        'source': str(PROJECT), 'copy': str(copy), 'serial': SERIAL, 'adb_port': PORT,
        'avd': AVD, 'api': 35, 'package': PKG, 'test_sha256': original[str(TEST)]}, indent=2))
    baseline = (copy / SOURCE).read_bytes()
    (evidence / 'MainActivity.baseline.java').write_bytes(baseline)
    avd_home = work / 'avd'
    avd_home.mkdir()
    env = dict(os.environ, JAVA_HOME=str(TOOLS / 'jdk/jdk-17.0.21+9/Contents/Home'),
               GRADLE_USER_HOME=os.environ.get('GRADLE_USER_HOME') or str(TOOLS / 'gradle-cache'), ANDROID_ADB_SERVER_PORT=PORT,
               ANDROID_AVD_HOME=str(work / 'avd'), ANDROID_EMULATOR_HOME=str(work / 'emulator-home'),
               ANDROID_USER_HOME=str(work / 'android-user'), ANDROID_SDK_ROOT=str(TOOLS / 'android-sdk'))
    env.pop('LIANYOUDU_RELEASE_SIGNING_DIR', None)
    sdk = TOOLS / 'android-sdk'
    subprocess.run([str(sdk / 'cmdline-tools/latest/bin/avdmanager'), 'create', 'avd',
                    '--name', AVD, '--package', 'system-images;android-35;google_apis;arm64-v8a',
                    '--device', 'pixel'], input='no\n', text=True, env=env, check=True)
    (copy / 'local.properties').write_text(f'sdk.dir={TOOLS / "android-sdk"}\n')
    adb = [str(TOOLS / 'android-sdk/platform-tools/adb'), '-P', PORT]
    device = adb + ['-s', SERIAL]
    gradle = ['nice', '-n', '15', str(TOOLS / 'gradle-8.11.1/bin/gradle'),
              '--no-daemon', '--max-workers=1', '-Dorg.gradle.parallel=false',
              '-Dorg.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8']
    if args.offline:
        gradle.append('--offline')
    results = []
    summary = {'baseline': False, 'mutants': results, 'restored': False, 'source_unchanged': False}
    server_owned = False
    emulator = None

    def save():
        (evidence / 'results.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')

    def command(argv, timeout=45, check=True):
        return subprocess.run(argv, env=env, cwd=copy, capture_output=True, text=True,
                              timeout=timeout, check=check)

    def build(label):
        with (evidence / (label + '.build.log')).open('w') as log:
            subprocess.run(gradle + ['-PcapabilityAudit', 'assembleDebug', 'assembleDebugAndroidTest'],
                           cwd=copy, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=240, check=True)
        require(hashlib.sha256((copy / TEST).read_bytes()).hexdigest() == original[str(TEST)],
                'Test source changed between variants')
        metadata = json.loads((copy / 'app/build/outputs/apk/debug/output-metadata.json').read_text())
        require(metadata['applicationId'] == PKG, 'Refuse installation: package ID mismatch')

    def run_case(label, case):
        name = command(device + ['emu', 'avd', 'name']).stdout.splitlines()[0].strip()
        require(name == AVD, 'Refuse operations on an unowned emulator')
        with (evidence / (label + '.run.log')).open('w') as log:
            commands = [device + ['install', '-r', str(copy / 'app/build/outputs/apk/debug/app-debug.apk')],
                        device + ['install', '-r', str(copy / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')],
                        device + ['shell', 'pm', 'clear', PKG]]
            commands += [device + ['shell', 'pm', 'grant', PKG, 'android.permission.' + permission]
                         for permission in ('POST_NOTIFICATIONS', 'BLUETOOTH_CONNECT', 'BLUETOOTH_SCAN')]
            for cmd in commands:
                completed = command(cmd, timeout=90)
                log.write(completed.stdout + completed.stderr)
            completed = command(device + ['shell', 'am', 'instrument', '-w', '-e', 'case', case,
                                          PKG + '.test/com.lianyoudu.app.CapabilityInstrumentation'], timeout=150)
            log.write(completed.stdout + completed.stderr)
        return completed.stdout + completed.stderr

    def clean_run(label):
        build(label)
        output = run_case(label, 'all')
        require('PASS capability all' in output and 'FAIL capability' not in output,
                label + ': normal version failed; inspect run log')
        print(label + ': ' + next(x for x in output.splitlines() if 'PASS capability' in x), flush=True)

    def check_default_config():
        # Evaluate existing variants in the COPY, without running their tasks or installing them.
        init = work / 'inspect-variants.gradle'
        init.write_text('''gradle.projectsEvaluated {
    def a = gradle.rootProject.project(':app').extensions.getByName('android').defaultConfig
    println('CAPABILITY_CONFIG=' + a.applicationId + '|' + a.testInstrumentationRunner)
}
''')
        for label, flags, expected in [
            ('default', [], 'com.lianyoudu.app'),
            ('device', ['-PdeviceAudit'], 'com.lianyoudu.app.qa'),
            ('isolated', ['-PisolatedAudit'], 'com.lianyoudu.app.audit')]:
            completed = command(gradle + flags + ['-I', str(init), 'help'], timeout=120)
            (evidence / ('config-' + label + '.log')).write_text(completed.stdout + completed.stderr)
            require('CAPABILITY_CONFIG=' + expected + '|com.lianyoudu.app.AuditInstrumentation' in completed.stdout,
                    'Existing build configuration changed: ' + label)
        invalid = command(gradle + ['-PcapabilityAudit', '-PdeviceAudit', 'help'], timeout=120, check=False)
        (evidence / 'config-conflict.log').write_text(invalid.stdout + invalid.stderr)
        require(invalid.returncode != 0 and 'cannot be combined' in invalid.stderr,
                'Conflicting device and capability flags must be rejected')
        summary['existing_configs_preserved'] = True
        print('existing default/device/isolated configurations preserved; conflicting flags rejected', flush=True)

    try:
        check_default_config()
        command(adb + ['--one-device', 'CAPABILITY_MERGED_NO_USB', 'start-server'])
        server_owned = True
        with (evidence / 'emulator.log').open('w') as log:
            emulator = subprocess.Popen(['nice', '-n', '15', str(TOOLS / 'android-sdk/emulator/emulator'),
                '-avd', AVD, '-ports', '5580,5581', '-no-window', '-no-audio', '-no-snapshot',
                '-no-boot-anim', '-gpu', 'swiftshader_indirect', '-cores', '2', '-memory', '2048'],
                env=env, stdout=log, stderr=subprocess.STDOUT)
        end = time.monotonic() + 150
        while True:
            require(emulator.poll() is None, 'Owned emulator exited before boot')
            try:
                boot = command(device + ['shell', 'getprop', 'sys.boot_completed'], timeout=5, check=False)
                if boot.returncode == 0 and boot.stdout.strip() == '1':
                    break
            except subprocess.TimeoutExpired:
                pass
            require(time.monotonic() < end, 'Owned emulator boot timeout')
            time.sleep(1)
        clean_run('baseline')
        summary['baseline'] = True
        save()
        try:
            for mutation in json.loads((copy / 'scripts/capability/mutations.json').read_text()):
                name, case = mutation['name'], mutation['case']
                normal = baseline.decode()
                require(normal.count(mutation['old']) == 1, name + ': mutation anchor changed; manual review required')
                changed = normal.replace(mutation['old'], mutation['new'])
                if name == 'bodyweight_extra_input':
                    description = 'e.name + "第" + (j + 1) + "组重量",'
                    require(changed.count(description) == 1, 'Weight label anchor changed')
                    changed = changed.replace(description, '"",')
                (copy / SOURCE).write_text(changed)
                (evidence / (name + '.patch')).write_text(''.join(difflib.unified_diff(
                    normal.splitlines(True), changed.splitlines(True), fromfile='MainActivity.baseline.java', tofile='MainActivity.mutant.java')))
                build(name)  # Compilation failures are never counted as detected defects.
                output = run_case(name, case)
                detected = detected_fault(output, case, mutation['expected_failure'])
                results.append({'mutation': name, 'detected': detected, 'build_passed': True,
                                'expected_failure': mutation['expected_failure'],
                                'source_sha256': hashlib.sha256(changed.encode()).hexdigest()})
                save()
                print(name + ': ' + ('DETECTED' if detected else 'NOT DETECTED'), flush=True)
                require(detected, name + ': did not fail at expected assertion')
        finally:
            (copy / SOURCE).write_bytes(baseline)
            clean_run('restored')
            summary['restored'] = True
            save()
        require(len(results) == 6 and all(r['detected'] for r in results), 'Incomplete mutation matrix')
        summary['source_unchanged'] = hashes(PROJECT) == original
        require(summary['source_unchanged'], 'Original project changed during verification; results describe snapshot only')
        artifacts = [copy / TEST, copy / SOURCE, copy / 'app/build/outputs/apk/debug/app-debug.apk',
                     copy / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
        (evidence / 'final-sha256.json').write_text(json.dumps({str(p.relative_to(copy)):hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts},indent=2))
    finally:
        (copy / SOURCE).write_bytes(baseline)
        if emulator is not None and emulator.poll() is None:
            # Terminate only the process we launched, never an existing emulator or default adb server.
            try:
                command(device + ['emu', 'kill'], timeout=10, check=False)
            except (subprocess.TimeoutExpired, OSError):
                pass
            try:
                emulator.wait(timeout=20)
            except subprocess.TimeoutExpired:
                emulator.terminate()
                emulator.wait(timeout=20)
        if server_owned:
            command(adb + ['kill-server'], timeout=10, check=False)
        summary['owned_emulator_stopped'] = emulator is None or emulator.poll() is not None
        summary['source_unchanged'] = hashes(PROJECT) == original
        save()
    # The final hashes, logs and patches are in evidence. Release the successful run's scratch space.
    for scratch in (avd_home, copy, work / 'emulator-home', work / 'android-user'):
        if scratch.is_dir():
            shutil.rmtree(scratch)
    print('PASS merged capability verification; original project unchanged; owned emulator stopped', flush=True)


if __name__ == '__main__':
    main()
