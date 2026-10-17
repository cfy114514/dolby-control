#!/usr/bin/env python3
"""Build the fixed-table Android helper using an already-installed NDK only."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def ndk_toolchain(ndk):
    host = 'windows-x86_64' if os.name == 'nt' else 'darwin-x86_64' if os.uname().sysname == 'Darwin' else 'linux-x86_64'
    return ndk / 'toolchains/llvm/prebuilt' / host


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, default=Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/ndk/30.0.14904198')
    parser.add_argument('--fixture-dll', type=Path, help='Build a Windows fixture DLL instead of the Android binary')
    parser.add_argument('--cow-fixture', type=Path, help='Build a separate Android self-memory fixture')
    args = parser.parse_args()
    toolchain = ndk_toolchain(args.ndk)
    compiler = toolchain / ('bin/clang++.exe' if os.name == 'nt' else 'bin/clang++')
    if not compiler.is_file():
        parser.error('Existing NDK Clang not found; provide --ndk')
    if args.fixture_dll:
        if os.name != 'nt':
            parser.error('--fixture-dll currently targets the Windows host')
        output = args.fixture_dll.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        object_file = output.with_suffix('.obj')
        subprocess.run([str(compiler), '--target=x86_64-pc-windows-msvc', '-c',
                        '-fno-exceptions', '-fno-rtti', '-fno-stack-protector',
                        '-ffreestanding', '-O2', '-std=c++17',
                        str(ROOT / 'src/native/table_fixture.cpp'), '-o', str(object_file)], check=True)
        command = [str(toolchain / 'bin/ld.lld.exe'), '-flavor', 'link', '/dll',
                   '/noentry', '/nodefaultlib', '/out:' + str(output), str(object_file)]
    else:
        output = args.cow_fixture.resolve() if args.cow_fixture else ROOT / 'assets/backend/table-runtime'
        output.parent.mkdir(parents=True, exist_ok=True)
        command = [str(compiler), '--target=aarch64-linux-android26',
                   '--sysroot=' + str(toolchain / 'sysroot'), '-static-libstdc++',
                   '-fPIE', '-pie', '-O2', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                   '-Wl,-z,relro,-z,now', '-Wl,-z,max-page-size=16384',
                   str(ROOT / ('src/native/table_cow_fixture.cpp' if args.cow_fixture else 'src/native/table_runtime.cpp')), '-o', str(output)]
    subprocess.run(command, check=True)
    print(output)


if __name__ == '__main__':
    main()
