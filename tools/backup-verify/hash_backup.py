from pathlib import Path
import hashlib
import sys

expected = {
    "proinfo": 0x300000,
    "nvram": 0x500000,
    "persist": 0x3000000,
    "seccfg": 0x40000,
    "lk_a": 0x80000,
    "lk_b": 0x80000,
    "boot_a": 0x1000000,
    "boot_b": 0x1000000,
    "para": 0x80000,
    "logo": 0x800000,
    "dtbo_a": 0x100000,
    "dtbo_b": 0x100000,
    "expdb": 0xA00000,
    "frp": 0x100000,
    "tee_a": 0x500000,
    "tee_b": 0x500000,
    "kb": 0x200000,
    "dkb": 0x200000,
    "metadata": 0x22C0000,
    "system_a": 0x80000000,
    "system_b": 0x80000000,
    "vendor_a": 0x20000000,
    "vendor_b": 0x20000000,
    "userdata": 0x86F80000,
    "flashinfo": 0x1000000,
}

root = Path(sys.argv[1])
partitions = root / "partitions"
errors = []
for name, size in expected.items():
    path = partitions / f"{name}.bin"
    if not path.is_file():
        errors.append(f"Missing: {path}")
    elif path.stat().st_size != size:
        errors.append(f"Size mismatch: {path}: {path.stat().st_size} != {size}")

for path in sorted(root.rglob("*")):
    if not path.is_file() or path.name == "SHA256SUMS.txt":
        continue
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    with (root / "SHA256SUMS.txt").open("a", encoding="utf-8") as manifest:
        manifest.write(f"{digest.hexdigest()}  {path.relative_to(root).as_posix()}\n")

if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print(f"Verified all {len(expected)} GPT partitions; SHA-256 manifest written to {root / 'SHA256SUMS.txt'}")
