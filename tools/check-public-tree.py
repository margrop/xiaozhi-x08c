from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {".git", ".gradle", "build", ".cxx", ".idea", "node_modules", "__pycache__"}
FORBIDDEN_NAME_SUFFIXES = {
    ".jks", ".keystore", ".p12", ".pem", ".key", ".apk", ".aab", ".img", ".bin"
}
ALLOWED_BINARY_SUFFIXES = {".so", ".onnx"}
FORBIDDEN_TEXT_PATTERNS = {
    "private ipv4 address": re.compile(
        r"(?<![0-9])(?:10(?:\.[0-9]{1,3}){3}|"
        r"192\.168(?:\.[0-9]{1,3}){2}|"
        r"172\.(?:1[6-9]|2[0-9]|3[0-1])(?:\.[0-9]{1,3}){2})(?![0-9])"
    ),
    "mac address": re.compile(
        r"(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])"
    ),
    "known local signing password": re.compile(r"x08c-local"),
    "openai-style api key": re.compile(r"sk-[A-Za-z0-9_-]{20,}"),
    "minimax populated key": re.compile(r"MINIMAX_API_KEY\s*=\s*(?!<)[^\s#]+"),
    "minimax populated group": re.compile(r"MINIMAX_GROUP_ID\s*=\s*(?!<)[^\s#]+"),
    "qweather populated key": re.compile(r"QWEATHER_API_KEY\s*=\s*(?!<)[^\s#]+"),
    "home assistant populated token": re.compile(
        r"HOME_ASSISTANT_API_KEY\s*=\s*(?!<)[^\s#]+"
    ),
}
TEXT_SUFFIXES = {
    ".java", ".kt", ".xml", ".gradle", ".kts", ".md", ".py", ".yaml", ".yml",
    ".json", ".txt", ".properties", ".sh", ".cpp", ".h", ".hpp", ".c", ".cmake"
}

errors = []
for path in sorted(ROOT.rglob("*")):
    if any(part in SKIP_DIRS for part in path.relative_to(ROOT).parts):
        continue
    if not path.is_file():
        continue
    relative = path.relative_to(ROOT).as_posix()
    if path.name == Path(__file__).name:
        continue
    if path.suffix.lower() in FORBIDDEN_NAME_SUFFIXES:
        errors.append(f"Forbidden file type: {relative}")
        continue
    if path.suffix.lower() not in TEXT_SUFFIXES:
        continue
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        errors.append(f"Binary file is not allowlisted: {relative}")
        continue
    for label, pattern in FORBIDDEN_TEXT_PATTERNS.items():
        if pattern.search(content):
            errors.append(f"Forbidden {label}: {relative}")

if errors:
    print("Public-tree check failed:")
    for error in errors:
        print(" - " + error)
    sys.exit(1)
print("public_tree_ok")
