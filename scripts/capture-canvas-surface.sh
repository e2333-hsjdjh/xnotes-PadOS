#!/usr/bin/env bash
# Read-only ADB capture. Use a NEW output directory for each before/split/after sample.
set -u
umask 077

if [[ $# -ne 3 ]]; then
    echo "Usage: bash $0 SERIAL PACKAGE NEW_OUTPUT_DIRECTORY" >&2
    echo "Example: bash $0 DEVICE_SERIAL com.xnotes.movinkpad tmp/canvas-after" >&2
    exit 2
fi

serial=$1
package=$2
out=$3
adb_bin=${ADB:-adb}
if [[ ! $package =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]]; then
    echo "Invalid Android package name" >&2
    exit 2
fi
command -v "$adb_bin" >/dev/null || { echo "ADB executable not found: $adb_bin" >&2; exit 2; }
adb_cmd=("$adb_bin" -s "$serial")
if ! "${adb_cmd[@]}" get-state >/dev/null; then
    echo "Selected device is not available; no capture written" >&2
    exit 1
fi
if ! package_path=$("${adb_cmd[@]}" shell pm path "$package") || [[ $package_path != package:* ]]; then
    echo "Package $package is not installed on the selected device; no capture written" >&2
    exit 1
fi
# mkdir without -p refuses to overwrite an earlier sample.
if ! mkdir -- "$out"; then
    echo "Choose a new directory under an existing parent" >&2
    exit 2
fi
failures=0
capture() {
    local name=$1
    shift
    if "${adb_cmd[@]}" "$@" > "$out/$name" 2> "$out/$name.stderr"; then
        printf '%s\tOK\n' "$name" >> "$out/status.tsv"
    else
        printf '%s\tFAILED\n' "$name" >> "$out/status.tsv"
        failures=$((failures + 1))
    fi
}

# Capture pixels first. Do not open menus, rotate, clear logs, restart or force a redraw.
capture screen.png exec-out screencap -p
capture gl-log.txt logcat -d -v threadtime -s xnotes.gl GLSurfaceView SurfaceView BLASTBufferQueue OpenGLRenderer libEGL
capture surface-layers.txt shell dumpsys -t 15 SurfaceFlinger --list
capture surfaceflinger.txt shell dumpsys -t 15 SurfaceFlinger
capture gfxinfo.txt shell dumpsys -t 15 gfxinfo "$package"
capture package.txt shell dumpsys -t 15 package "$package"
for prop in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk ro.build.fingerprint ro.hardware ro.soc.model; do
    capture "$prop.txt" shell getprop "$prop"
done
printf 'Device: %s\nPackage: %s\nUTC capture end: %s\n' "$serial" "$package" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "$out/capture.txt"
echo "Saved capture to $out ($failures failed commands; see status.tsv)"
echo "Contains screen content and device/app metadata. Review before sharing. Nothing was uploaded."
[[ $failures -eq 0 ]]
