#!/usr/bin/env bash
# Prints the UDID of an available iPhone simulator, for xcodebuild -destination "platform=iOS Simulator,id=<UDID>".
#
#   ci/ios-simulator.sh [device name]      default "iPhone 15"
#
# A destination given by name alone matches one simulator per installed runtime; Xcode 15 then
# warns "Using the first of multiple matching destinations" and may build and test for "My Mac"
# instead. This picks one device: the named device on the runtime of the iOS Simulator SDK of the
# selected Xcode, else any iPhone on that runtime, else the newest older runtime. The choice goes
# to stderr, the UDID to stdout.
set -euo pipefail

NAME="${1:-iPhone 15}"
SDK_VERSION="$(xcrun --sdk iphonesimulator --show-sdk-version)"
xcrun simctl list devices available -j | python3 -c '
import json, sys

name, sdk = sys.argv[1], tuple(int(x) for x in sys.argv[2].split("."))
devices = json.load(sys.stdin)["devices"]
candidates = []
for runtime, entries in devices.items():
    tail = runtime.rsplit(".", 1)[-1]
    if not tail.startswith("iOS-"):
        continue
    version = tuple(int(x) for x in tail[4:].split("-"))
    if version > sdk:
        continue  # newer than the SDK: this Xcode cannot run tests on it
    for d in entries:
        if d.get("isAvailable", True) and d["name"].startswith("iPhone"):
            candidates.append(((version == sdk, d["name"] == name, version), d, version))
if not candidates:
    sys.exit("no available iPhone simulator with iOS %s or older" % ".".join(map(str, sdk)))
_, device, version = max(candidates, key=lambda c: c[0])
print("simulator %s, iOS %s, %s (SDK %s)" % (device["name"], ".".join(map(str, version)), device["udid"],
                                             ".".join(map(str, sdk))), file=sys.stderr)
print(device["udid"])
' "$NAME" "$SDK_VERSION"
