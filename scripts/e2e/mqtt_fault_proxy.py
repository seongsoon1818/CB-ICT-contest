#!/usr/bin/env python3
"""Local MQTT TCP proxy that drops one armed client PUBLISH before forwarding."""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import signal
from pathlib import Path


MQTT_PUBLISH_PACKET_TYPE = 3
MAX_REMAINING_LENGTH_BYTES = 4


def _mqtt_packet(buffer: bytearray) -> bytes | None:
    if len(buffer) < 2:
        return None

    multiplier = 1
    remaining_length = 0
    cursor = 1
    for _ in range(MAX_REMAINING_LENGTH_BYTES):
        if cursor >= len(buffer):
            return None
        encoded = buffer[cursor]
        cursor += 1
        remaining_length += (encoded & 0x7F) * multiplier
        if encoded & 0x80 == 0:
            packet_length = cursor + remaining_length
            if len(buffer) < packet_length:
                return None
            packet = bytes(buffer[:packet_length])
            del buffer[:packet_length]
            return packet
        multiplier *= 128
    raise ValueError("invalid MQTT remaining-length encoding")


class FaultProxy:
    def __init__(
        self,
        upstream_host: str,
        upstream_port: int,
        drop_next_publish_file: Path,
    ) -> None:
        self._upstream_host = upstream_host
        self._upstream_port = upstream_port
        self._drop_next_publish_file = drop_next_publish_file

    async def handle(
        self,
        client_reader: asyncio.StreamReader,
        client_writer: asyncio.StreamWriter,
    ) -> None:
        try:
            broker_reader, broker_writer = await asyncio.open_connection(
                self._upstream_host,
                self._upstream_port,
            )
        except OSError as error:
            print(f"MQTT_PROXY_UPSTREAM_CONNECT_FAILED={error}", flush=True)
            client_writer.close()
            await client_writer.wait_closed()
            return

        client_to_broker = asyncio.create_task(
            self._forward_client_packets(client_reader, broker_writer)
        )
        broker_to_client = asyncio.create_task(
            self._forward_bytes(broker_reader, client_writer)
        )
        tasks = {client_to_broker, broker_to_client}
        try:
            _, pending = await asyncio.wait(
                tasks,
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)
        finally:
            for writer in (client_writer, broker_writer):
                writer.close()
                with contextlib.suppress(ConnectionError, OSError):
                    await writer.wait_closed()

    async def _forward_client_packets(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        buffer = bytearray()
        while chunk := await reader.read(65_536):
            buffer.extend(chunk)
            while True:
                packet = _mqtt_packet(buffer)
                if packet is None:
                    break
                packet_type = packet[0] >> 4
                if (
                    packet_type == MQTT_PUBLISH_PACKET_TYPE
                    and self._drop_next_publish_file.exists()
                ):
                    self._drop_next_publish_file.unlink(missing_ok=True)
                    print("MQTT_PROXY_DROPPED_PUBLISH=1", flush=True)
                    return
                writer.write(packet)
                await writer.drain()

    @staticmethod
    async def _forward_bytes(
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        while chunk := await reader.read(65_536):
            writer.write(chunk)
            await writer.drain()


async def _run(args: argparse.Namespace) -> None:
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        with contextlib.suppress(NotImplementedError):
            loop.add_signal_handler(signum, stop.set)

    proxy = FaultProxy(
        args.upstream_host,
        args.upstream_port,
        args.drop_next_publish_file,
    )
    server = await asyncio.start_server(
        proxy.handle,
        args.listen_host,
        args.listen_port,
    )
    print(
        f"MQTT_PROXY_READY={args.listen_host}:{args.listen_port}"
        f"->{args.upstream_host}:{args.upstream_port}",
        flush=True,
    )
    async with server:
        await stop.wait()


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-host", default="127.0.0.1")
    parser.add_argument("--listen-port", type=int, required=True)
    parser.add_argument("--upstream-host", default="127.0.0.1")
    parser.add_argument("--upstream-port", type=int, required=True)
    parser.add_argument("--drop-next-publish-file", type=Path, required=True)
    return parser.parse_args()


if __name__ == "__main__":
    asyncio.run(_run(_arguments()))
