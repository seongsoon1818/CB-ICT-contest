from pathlib import Path
from types import SimpleNamespace

import pytest

import app.engine_factory as engine_factory
from app.engine_factory import InferenceEngineLoadError
from app.settings import Settings
from app.ultralytics_inference import (
    ULTRALYTICS_OUTPUT_ADAPTER,
    ULTRALYTICS_RUNTIME,
)


def model_settings() -> Settings:
    return Settings(
        backend_base_url=None,
        inference_mode="model",
        model_bundle_dir=Path("/opt/animalguard/models/current"),
    )


def fake_bundle(
    *,
    runtime: str = ULTRALYTICS_RUNTIME,
    output_adapter: str = ULTRALYTICS_OUTPUT_ADAPTER,
):
    return SimpleNamespace(
        manifest=SimpleNamespace(
            runtime=runtime,
            output_adapter=output_adapter,
        )
    )


def test_supported_bundle_constructs_ultralytics_adapter_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bundle = fake_bundle()
    engine = object()
    loaded_directories: list[Path] = []
    adapter_bundles: list[object] = []

    class RecordingLoader:
        def load(self, directory: Path):
            loaded_directories.append(directory)
            return bundle

    def adapter_factory(loaded_bundle: object):
        adapter_bundles.append(loaded_bundle)
        return engine

    monkeypatch.setattr(engine_factory, "ModelBundleLoader", RecordingLoader)
    monkeypatch.setattr(
        engine_factory,
        "UltralyticsInference",
        adapter_factory,
        raising=False,
    )

    result = engine_factory.create_inference_engine(model_settings())

    assert result is engine
    assert loaded_directories == [Path("/opt/animalguard/models/current")]
    assert adapter_bundles == [bundle]


@pytest.mark.parametrize(
    ("runtime", "output_adapter"),
    [
        ("unsupported-runtime", ULTRALYTICS_OUTPUT_ADAPTER),
        (ULTRALYTICS_RUNTIME, "unsupported-adapter"),
    ],
)
def test_unsupported_bundle_fails_without_constructing_adapter(
    monkeypatch: pytest.MonkeyPatch,
    runtime: str,
    output_adapter: str,
) -> None:
    adapter_calls = 0

    class RecordingLoader:
        def load(self, directory: Path):
            return fake_bundle(
                runtime=runtime,
                output_adapter=output_adapter,
            )

    def adapter_factory(bundle: object):
        nonlocal adapter_calls
        adapter_calls += 1
        return object()

    monkeypatch.setattr(engine_factory, "ModelBundleLoader", RecordingLoader)
    monkeypatch.setattr(
        engine_factory,
        "UltralyticsInference",
        adapter_factory,
        raising=False,
    )

    with pytest.raises(InferenceEngineLoadError, match="No runtime adapter"):
        engine_factory.create_inference_engine(model_settings())

    assert adapter_calls == 0


def test_mock_mode_does_not_load_bundle_or_construct_ultralytics(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def unexpected_call(*args, **kwargs):
        pytest.fail("model dependencies must not be used in mock mode")

    monkeypatch.setattr(engine_factory, "ModelBundleLoader", unexpected_call)
    monkeypatch.setattr(
        engine_factory,
        "UltralyticsInference",
        unexpected_call,
        raising=False,
    )

    engine = engine_factory.create_inference_engine(
        Settings(backend_base_url=None, inference_mode="mock")
    )

    assert engine.metadata.mode == "mock"
