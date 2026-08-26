from __future__ import annotations

from contextlib import ExitStack
from threading import Event, Lock
from types import SimpleNamespace
from typing import Callable, Protocol

from .settings import Settings


class CommandInterruptedError(RuntimeError):
    pass


class GPIOBusyError(RuntimeError):
    pass


class GPIOAdapter(Protocol):
    def rotate_left(self) -> None: ...

    def rotate_right(self) -> None: ...

    def sound_alert(self, duration_ms: int) -> None: ...

    def deterrent_full(self, duration_ms: int) -> None: ...

    def stop_deterrent(self) -> None: ...

    def close(self) -> None: ...


class GpioZeroAdapter:
    """BCM GPIO adapter for a digital speaker/relay, motor and pan servo."""

    def __init__(
        self,
        settings: Settings,
        *,
        gpiozero: SimpleNamespace | None = None,
        waiter: Callable[[float], bool] | None = None,
    ) -> None:
        gpiozero = gpiozero or _load_gpiozero()
        with ExitStack() as resources:
            motor = gpiozero.Motor(
                forward=settings.motor_in1_pin,
                backward=settings.motor_in2_pin,
                pwm=False,
            )
            resources.callback(motor.close)
            motor_sleep = gpiozero.OutputDevice(
                settings.motor_sleep_pin,
                initial_value=False,
            )
            resources.callback(motor_sleep.close)
            speaker = gpiozero.OutputDevice(
                settings.speaker_pin,
                initial_value=False,
            )
            resources.callback(speaker.close)
            servo = gpiozero.AngularServo(
                settings.servo_pin,
                initial_angle=0,
                min_angle=settings.servo_min_angle,
                max_angle=settings.servo_max_angle,
            )
            resources.callback(servo.close)
            resources.pop_all()

        self._motor = motor
        self._motor_sleep = motor_sleep
        self._speaker = speaker
        self._servo = servo
        self._servo_step = settings.servo_step_degrees
        self._servo_min = settings.servo_min_angle
        self._servo_max = settings.servo_max_angle
        self._current_angle = 0.0
        self._state_lock = Lock()
        self._timed_lock = Lock()
        self._stop_event = Event()
        self._waiter = waiter or self._stop_event.wait
        self._closed = False

    @property
    def current_angle(self) -> float:
        with self._state_lock:
            return self._current_angle

    def rotate_left(self) -> None:
        self._rotate(-self._servo_step)

    def rotate_right(self) -> None:
        self._rotate(self._servo_step)

    def sound_alert(self, duration_ms: int) -> None:
        self._begin_timed_action()
        try:
            with self._state_lock:
                self._speaker.on()
            self._wait_for_completion(duration_ms)
        finally:
            with self._state_lock:
                self._speaker.off()
            self._timed_lock.release()

    def deterrent_full(self, duration_ms: int) -> None:
        self._begin_timed_action()
        try:
            with self._state_lock:
                self._motor_sleep.on()
                self._motor.forward()
                self._speaker.on()
            self._wait_for_completion(duration_ms)
        finally:
            with self._state_lock:
                self._motor.stop()
                self._speaker.off()
                self._motor_sleep.off()
            self._timed_lock.release()

    def stop_deterrent(self) -> None:
        self._stop_event.set()
        with self._state_lock:
            self._motor.stop()
            self._speaker.off()
            self._motor_sleep.off()

    def close(self) -> None:
        with self._state_lock:
            if self._closed:
                return
            self._closed = True
        self.stop_deterrent()
        self._servo.close()
        self._speaker.close()
        self._motor.close()
        self._motor_sleep.close()

    def _rotate(self, delta: float) -> None:
        with self._state_lock:
            self._require_open()
            next_angle = min(
                self._servo_max,
                max(self._servo_min, self._current_angle + delta),
            )
            self._servo.angle = next_angle
            self._current_angle = next_angle

    def _begin_timed_action(self) -> None:
        self._require_open()
        if not self._timed_lock.acquire(blocking=False):
            raise GPIOBusyError("another timed GPIO action is active")
        self._stop_event.clear()

    def _wait_for_completion(self, duration_ms: int) -> None:
        if self._waiter(duration_ms / 1000):
            raise CommandInterruptedError("timed GPIO action was stopped")

    def _require_open(self) -> None:
        if self._closed:
            raise RuntimeError("GPIO adapter is closed")


def _load_gpiozero() -> SimpleNamespace:
    try:
        from gpiozero import AngularServo, Motor, OutputDevice
    except ImportError as exc:
        raise RuntimeError(
            "gpiozero is required only when the production GPIO adapter starts"
        ) from exc
    return SimpleNamespace(
        AngularServo=AngularServo,
        Motor=Motor,
        OutputDevice=OutputDevice,
    )
