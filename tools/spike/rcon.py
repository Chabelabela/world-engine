"""Minimal RCON client for the M1b spike's local dedicated server (spike branch only).

Usage: python rcon.py <password> "<command>" [more commands...]
Connects to 127.0.0.1:25575 and prints each command's reply.
"""
import socket
import struct
import sys

HOST, PORT = "127.0.0.1", 25575
LOGIN, COMMAND = 3, 2


def packet(request_id: int, kind: int, body: str) -> bytes:
    payload = struct.pack("<ii", request_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def read_packet(sock: socket.socket) -> tuple[int, int, str]:
    def exactly(n: int) -> bytes:
        data = b""
        while len(data) < n:
            chunk = sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("RCON connection closed")
            data += chunk
        return data

    (length,) = struct.unpack("<i", exactly(4))
    request_id, kind = struct.unpack("<ii", exactly(8))
    body = exactly(length - 8)[:-2].decode("utf-8", errors="replace")
    return request_id, kind, body


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    password, commands = sys.argv[1], sys.argv[2:]
    with socket.create_connection((HOST, PORT), timeout=10) as sock:
        sock.sendall(packet(1, LOGIN, password))
        request_id, _, _ = read_packet(sock)
        if request_id == -1:
            print("RCON login refused")
            return 1
        for i, command in enumerate(commands, start=2):
            sock.sendall(packet(i, COMMAND, command))
            _, _, body = read_packet(sock)
            print(f"> {command}\n{body}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
