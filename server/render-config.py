from pathlib import Path
import json
import os
import sys

required = [
    "MINIMAX_API_KEY",
    "MINIMAX_GROUP_ID",
    "MINIMAX_LLM_MODEL",
    "MINIMAX_TTS_MODEL",
    "MINIMAX_TTS_VOICE",
]

optional = {
    "QWEATHER_API_HOST": "mj7p3y7naa.re.qweatherapi.com",
    "QWEATHER_API_KEY": "",
    "QWEATHER_DEFAULT_LOCATION": "广州",
    "HOME_ASSISTANT_BASE_URL": "",
    "HOME_ASSISTANT_API_KEY": "",
    "HOME_ASSISTANT_DEVICES": "",
}

env_path = Path(sys.argv[1])
template_path = Path(sys.argv[2])
output_path = Path(sys.argv[3])
values = {}
for line in env_path.read_text(encoding="utf-8").splitlines():
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    if line.startswith("export "):
        line = line[7:].lstrip()
    key, separator, value = line.partition("=")
    if separator:
        values[key] = value.strip()

missing = [key for key in required if not values.get(key)]
if missing:
    raise SystemExit("Missing required settings: " + ", ".join(missing))

content = template_path.read_text(encoding="utf-8")
for key in required:
    content = content.replace("__" + key + "__", values[key])
for key, default in optional.items():
    content = content.replace("__" + key + "__", values.get(key, default))

ha_devices = values.get("HOME_ASSISTANT_DEVICES", "").strip()
device_entries = [entry.strip() for entry in ha_devices.split(";") if entry.strip()]
if device_entries:
    devices_yaml = "\n" + "\n".join(
        "      - " + json.dumps(entry, ensure_ascii=False) for entry in device_entries
    )
else:
    devices_yaml = "[]"
content = content.replace("__HOME_ASSISTANT_DEVICES_YAML__", devices_yaml)

if "__MINIMAX_" in content or "__QWEATHER_" in content or "__HOME_ASSISTANT_" in content:
    raise SystemExit("Unresolved server placeholder remains")

flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
fd = os.open(output_path, flags, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as output:
    output.write(content)
print(f"Configuration written to {output_path}; secrets were not printed")
