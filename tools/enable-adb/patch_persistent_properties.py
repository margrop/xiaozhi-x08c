from pathlib import Path
import sys


def read_varint(data, offset):
    value = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7
        if shift > 35:
            raise ValueError("Invalid varint")


def write_varint(value):
    result = bytearray()
    while value >= 0x80:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def read_record(data):
    offset = 0
    name = None
    value = None
    while offset < len(data):
        tag, offset = read_varint(data, offset)
        length, offset = read_varint(data, offset)
        payload = data[offset:offset + length]
        offset += length
        if tag == 0x0A:
            name = payload.decode("utf-8")
        elif tag == 0x12:
            value = payload.decode("utf-8")
        else:
            raise ValueError(f"Unknown record field: {tag:#x}")
    if name is None or value is None or offset != len(data):
        raise ValueError("Incomplete property record")
    return name, value


def encode_property(name, value):
    name_data = name.encode("utf-8")
    value_data = value.encode("utf-8")
    record = b"\x0a" + write_varint(len(name_data)) + name_data
    record += b"\x12" + write_varint(len(value_data)) + value_data
    return b"\x0a" + write_varint(len(record)) + record


source = Path(sys.argv[1]).read_bytes()
offset = 0
properties = []
while offset < len(source):
    tag, offset = read_varint(source, offset)
    if tag != 0x0A:
        raise ValueError(f"Unexpected top-level field: {tag:#x}")
    length, offset = read_varint(source, offset)
    record = source[offset:offset + length]
    offset += length
    properties.append(read_record(record))

roundtrip = b"".join(encode_property(name, value) for name, value in properties)
if roundtrip != source:
    raise RuntimeError("Round-trip validation failed")

updates = {
    "persist.sys.usb.config": "mtp,adb",
}
properties = [(name, value) for name, value in properties if name not in updates]
properties.extend(updates.items())
modified = b"".join(encode_property(name, value) for name, value in properties)
Path(sys.argv[2]).write_bytes(modified)

print(f"Round-trip validated {len(properties) - len(updates)} original entries")
for name, value in updates.items():
    print(f"Added {name}={value}")
print(f"Size: {len(source)} -> {len(modified)} bytes")
