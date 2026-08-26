from types import SimpleNamespace

import pytest

from animalguard_embedded.gpio_adapter import GpioZeroAdapter
from tests.helpers import settings


class FakeOutputDevice:
    def __init__(self, pin, initial_value=False):
        self.pin = pin
        self.value = initial_value
        self.events = []
        self.closed = False

    def on(self):
        self.value = True
        self.events.append("on")

    def off(self):
        self.value = False
        self.events.append("off")

    def close(self):
        self.closed = True


class FakeMotor:
    def __init__(self, forward, backward, pwm):
        self.forward_pin = forward
        self.backward_pin = backward
        self.pwm = pwm
        self.events = []
        self.closed = False

    def forward(self):
        self.events.append("forward")

    def stop(self):
        self.events.append("stop")

    def close(self):
        self.closed = True


class FakeServo:
    def __init__(self, pin, initial_angle, min_angle, max_angle):
        self.pin = pin
        self.angle = initial_angle
        self.min_angle = min_angle
        self.max_angle = max_angle
        self.closed = False

    def close(self):
        self.closed = True


class FakeGpioZero:
    def __init__(self):
        self.outputs = {}
        self.motor = None
        self.servo = None

    def namespace(self):
        def output_device(pin, initial_value=False):
            device = FakeOutputDevice(pin, initial_value)
            self.outputs[pin] = device
            return device

        def motor(forward, backward, pwm):
            self.motor = FakeMotor(forward, backward, pwm)
            return self.motor

        def servo(pin, initial_angle, min_angle, max_angle):
            self.servo = FakeServo(pin, initial_angle, min_angle, max_angle)
            return self.servo

        return SimpleNamespace(
            OutputDevice=output_device,
            Motor=motor,
            AngularServo=servo,
        )


def make_adapter():
    fake = FakeGpioZero()
    adapter = GpioZeroAdapter(
        settings(),
        gpiozero=fake.namespace(),
        waiter=lambda timeout: False,
    )
    return adapter, fake


def test_left_decreases_right_increases_and_both_clamp():
    adapter, fake = make_adapter()

    for _ in range(20):
        adapter.rotate_left()
    assert adapter.current_angle == -60
    assert fake.servo.angle == -60

    for _ in range(30):
        adapter.rotate_right()
    assert adapter.current_angle == 60
    assert fake.servo.angle == 60

    adapter.close()


def test_sound_alert_uses_only_digital_speaker_then_turns_it_off():
    adapter, fake = make_adapter()

    adapter.sound_alert(2000)

    speaker = fake.outputs[23]
    motor_sleep = fake.outputs[22]
    assert speaker.events == ["on", "off"]
    assert speaker.value is False
    assert fake.motor.events == []
    assert motor_sleep.events == []
    adapter.close()


def test_deterrent_full_runs_motor_and_speaker_then_turns_both_off():
    adapter, fake = make_adapter()

    adapter.deterrent_full(5000)

    assert fake.outputs[22].events == ["on", "off"]
    assert fake.outputs[23].events == ["on", "off"]
    assert fake.motor.events == ["forward", "stop"]
    adapter.close()


def test_stop_turns_motor_and_speaker_off_without_changing_servo():
    adapter, fake = make_adapter()
    adapter.rotate_right()
    servo_angle = adapter.current_angle

    adapter.stop_deterrent()

    assert fake.motor.events == ["stop"]
    assert fake.outputs[22].events == ["off"]
    assert fake.outputs[23].events == ["off"]
    assert adapter.current_angle == servo_angle
    assert fake.servo.angle == servo_angle
    adapter.close()


def test_close_is_idempotent_and_releases_every_gpio_resource():
    adapter, fake = make_adapter()

    adapter.close()
    adapter.close()

    assert fake.motor.closed is True
    assert fake.servo.closed is True
    assert all(output.closed for output in fake.outputs.values())


def test_partial_initialization_failure_releases_created_gpio_resources():
    fake = FakeGpioZero()
    gpiozero = fake.namespace()

    def fail_servo(*args, **kwargs):
        del args, kwargs
        raise RuntimeError("fake servo initialization failure")

    gpiozero.AngularServo = fail_servo

    with pytest.raises(RuntimeError, match="fake servo initialization failure"):
        GpioZeroAdapter(settings(), gpiozero=gpiozero)

    assert fake.motor.closed is True
    assert all(output.closed for output in fake.outputs.values())
