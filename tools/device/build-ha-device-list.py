from pathlib import Path
import json
import os
import re
import sys
import urllib.request

SAFE_DOMAINS = {"light", "switch", "climate", "fan", "media_player"}
BREAD_MACHINE_PATTERN = re.compile(r"面包|toaster", re.IGNORECASE)
BREAD_MACHINE_DOMAINS = {"button", "number", "select", "sensor", "switch"}
# Camera-adjacent switches (motion tracking, indicator light on a camera, etc.)
# are excluded even though they live in the switch domain.
EXCLUDED_PATTERN = re.compile(r"camera|摄像机|camera_control", re.IGNORECASE)


def load_env(path):
    values = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def fetch_states(base_url, api_key):
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/states",
        headers={"Authorization": "Bearer " + api_key},
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))


def clean_name(value):
    value = re.sub(r"[,;\r\n\t]", " ", value or "").strip()
    return value or "未命名设备"


def main():
    env_path = Path(sys.argv[1])
    env = load_env(env_path)
    base_url = env.get("HOME_ASSISTANT_BASE_URL", "").strip()
    api_key = env.get("HOME_ASSISTANT_API_KEY", "").strip()
    location = clean_name(env.get("HOME_ASSISTANT_DEVICE_LOCATION", "家居"))
    if not location:
        location = "家居"

    if not base_url or not api_key:
        raise SystemExit("HOME_ASSISTANT_BASE_URL and HOME_ASSISTANT_API_KEY must be set")

    states = fetch_states(base_url, api_key)
    selected = []
    seen = set()
    for state in states:
        entity_id = state.get("entity_id", "")
        if not entity_id or "." not in entity_id:
            continue
        domain = entity_id.split(".", 1)[0]
        attributes = state.get("attributes") or {}
        friendly_name = clean_name(attributes.get("friendly_name") or entity_id)
        required = bool(BREAD_MACHINE_PATTERN.search(entity_id) or BREAD_MACHINE_PATTERN.search(friendly_name))
        allowed_bread_machine = required and domain in BREAD_MACHINE_DOMAINS
        if domain not in SAFE_DOMAINS and not allowed_bread_machine:
            continue
        if EXCLUDED_PATTERN.search(entity_id) or EXCLUDED_PATTERN.search(friendly_name):
            continue
        if entity_id in seen:
            continue
        seen.add(entity_id)
        selected.append((domain, friendly_name, entity_id))

    selected.sort(key=lambda item: (item[0], item[1], item[2]))
    device_value = ";".join(
        "{},{},{}".format(location, friendly_name, entity_id)
        for _, friendly_name, entity_id in selected
    )

    lines = env_path.read_text(encoding="utf-8").splitlines()
    output = []
    replaced = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("HOME_ASSISTANT_DEVICES=") or stripped.startswith("#HOME_ASSISTANT_DEVICES="):
            if not replaced:
                output.append("HOME_ASSISTANT_DEVICES=" + device_value)
                replaced = True
            continue
        output.append(line)
    if not replaced:
        output.append("HOME_ASSISTANT_DEVICES=" + device_value)

    env_path.write_text("\n".join(output) + "\n", encoding="utf-8")
    os.chmod(env_path, 0o600)

    required_matches = [item for item in selected if BREAD_MACHINE_PATTERN.search(item[1]) or BREAD_MACHINE_PATTERN.search(item[2])]
    print(f"ha_entities_total={len(states)}")
    print(f"selected_devices={len(selected)}")
    print(f"required_matches={len(required_matches)}")
    for _, friendly_name, entity_id in required_matches[:20]:
        print("required=" + friendly_name + " -> " + entity_id)
    print("env_updated=" + str(env_path))


if __name__ == "__main__":
    main()
