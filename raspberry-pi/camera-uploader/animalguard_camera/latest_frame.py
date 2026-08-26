import threading
from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class CapturedFrame:
    sequence: int
    captured_at: datetime
    jpeg_bytes: bytes

    def __post_init__(self) -> None:
        if not isinstance(self.sequence, int) or isinstance(self.sequence, bool):
            raise ValueError("sequence must be an integer")
        if self.sequence <= 0:
            raise ValueError("sequence must be greater than 0")
        if self.captured_at.tzinfo is None or self.captured_at.utcoffset() is None:
            raise ValueError("captured_at must include a timezone")
        if not isinstance(self.jpeg_bytes, bytes) or not self.jpeg_bytes:
            raise ValueError("jpeg_bytes must be non-empty bytes")


class LatestFrameSlot:
    """A single-consumer slot that retains at most the latest captured frame."""

    def __init__(self) -> None:
        self._condition = threading.Condition()
        self._frame: CapturedFrame | None = None
        self._last_published_sequence = 0
        self._last_delivered_sequence = 0
        self._closed = False

    def publish(self, frame: CapturedFrame) -> bool:
        with self._condition:
            if self._closed:
                raise RuntimeError("latest frame slot is closed")
            if frame.sequence <= self._last_published_sequence:
                raise ValueError("published frame sequence must strictly increase")

            overwritten = (
                self._frame is not None
                and self._frame.sequence > self._last_delivered_sequence
            )
            self._frame = frame
            self._last_published_sequence = frame.sequence
            self._condition.notify()
            return overwritten

    def wait_for_newer(
        self,
        last_sequence: int,
        stop_event: threading.Event,
    ) -> CapturedFrame | None:
        with self._condition:
            while True:
                if stop_event.is_set() or self._closed:
                    return None
                if (
                    self._frame is not None
                    and self._frame.sequence > last_sequence
                    and self._frame.sequence > self._last_delivered_sequence
                ):
                    self._last_delivered_sequence = self._frame.sequence
                    return self._frame
                self._condition.wait()

    def close(self) -> None:
        with self._condition:
            self._closed = True
            self._condition.notify_all()
