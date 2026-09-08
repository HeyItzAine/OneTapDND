"""Check constructors that Android libraries load reflectively in a built APK."""

import argparse
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile


REQUIRED = {
    "androidx.work.impl.WorkDatabase_Impl": (),
    "com.example.onetapdnd.PlaceRegistrationWorker": (
        "android.content.Context",
        "androidx.work.WorkerParameters",
    ),
    "com.example.onetapdnd.PauseResumeWorker": (
        "android.content.Context",
        "androidx.work.WorkerParameters",
    ),
    "com.example.onetapdnd.AdaptiveLocationWorker": (
        "android.content.Context",
        "androidx.work.WorkerParameters",
    ),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--dexdump", required=True, type=Path)
    args = parser.parse_args()
    found = set()
    with ZipFile(args.apk) as apk, tempfile.TemporaryDirectory() as scratch:
        for entry in apk.infolist():
            if not entry.filename.endswith(".dex"):
                continue
            dex = Path(scratch) / Path(entry.filename).name
            dex.write_bytes(apk.read(entry))
            result = subprocess.run(
                [str(args.dexdump), "-l", "xml", str(dex)],
                check=True, capture_output=True,
            )
            root = ET.fromstring(result.stdout)
            for package in root.findall("package"):
                for cls in package.findall("class"):
                    name = f"{package.get('name')}.{cls.get('name')}"
                    if name not in REQUIRED or cls.get("abstract") != "false":
                        continue
                    for constructor in cls.findall("constructor"):
                        parameters = tuple(p.get("type") for p in constructor.findall("parameter"))
                        if constructor.get("visibility") == "public" and parameters == REQUIRED[name]:
                            found.add(name)
    missing = REQUIRED.keys() - found
    if missing:
        raise SystemExit("Missing public release constructors: " + ", ".join(sorted(missing)))
    print("Release APK retains the database and background worker constructors.")


if __name__ == "__main__":
    main()
