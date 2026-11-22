from pathlib import Path, PurePosixPath
import stat
import sys
import zipfile

archive = Path(sys.argv[1])
target = Path(sys.argv[2])

with zipfile.ZipFile(archive) as source:
    bad_file = source.testzip()
    if bad_file:
        raise RuntimeError(f"CRC check failed: {bad_file}")

    for info in source.infolist():
        name = PurePosixPath(info.filename)
        if name.is_absolute() or ".." in name.parts or ":" in name.parts[0]:
            raise RuntimeError(f"Unsafe archive path: {info.filename}")
        if stat.S_ISLNK(info.external_attr >> 16):
            raise RuntimeError(f"Symbolic link rejected: {info.filename}")

    if target.exists() and any(target.iterdir()):
        raise RuntimeError(f"Target is not empty: {target}")
    target.mkdir(parents=True, exist_ok=True)
    source.extractall(target)

print(f"Verified and extracted {len(source.infolist())} entries to {target}")
