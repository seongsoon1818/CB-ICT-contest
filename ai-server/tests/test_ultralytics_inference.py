import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from PIL import Image

from app.inference import DecodedFrame
from app.model_bundle import ClassMap, ModelBundle, ModelManifest
from app.ultralytics_inference import (
    UltralyticsInference,
    UltralyticsInferenceError,
    load_ultralytics_model,
)


class FakeTensor:
    def __init__(self, values: list[Any]) -> None:
        self._values = values

    def tolist(self) -> list[Any]:
        return self._values


class FakeBoxes:
    def __init__(
        self,
        *,
        xyxy: list[list[float]],
        confidence: list[float],
        class_ids: list[float],
    ) -> None:
        self.xyxy = FakeTensor(xyxy)
        self.conf = FakeTensor(confidence)
        self.cls = FakeTensor(class_ids)


class FakeResult:
    def __init__(self, boxes: FakeBoxes | None) -> None:
        self.boxes = boxes


class FakeModel:
    def __init__(
        self,
        *,
        task: str = "detect",
        names: dict[int, str] | None = None,
        boxes: FakeBoxes | None = None,
    ) -> None:
        self.task = task
        self.names = names or {0: "magpie", 1: "wild_boar"}
        self._boxes = boxes
        self.predict_calls: list[dict[str, Any]] = []

    def predict(self, **kwargs: Any) -> list[FakeResult]:
        self.predict_calls.append(kwargs)
        return [FakeResult(self._boxes)]


class BlockingModel(FakeModel):
    def __init__(self) -> None:
        super().__init__(boxes=None)
        self.first_started = threading.Event()
        self.second_started = threading.Event()
        self.release_first = threading.Event()
        self._call_lock = threading.Lock()
        self._call_count = 0

    def predict(self, **kwargs: Any) -> list[FakeResult]:
        with self._call_lock:
            self._call_count += 1
            call_number = self._call_count
        if call_number == 1:
            self.first_started.set()
            if not self.release_first.wait(timeout=2):
                raise TimeoutError("test did not release the first inference")
        else:
            self.second_started.set()
        return super().predict(**kwargs)


class FailingModel(FakeModel):
    def predict(self, **kwargs: Any) -> list[FakeResult]:
        raise RuntimeError("secret model failure")


class ResultCountModel(FakeModel):
    def __init__(self, count: int) -> None:
        super().__init__(boxes=None)
        self._count = count

    def predict(self, **kwargs: Any) -> list[FakeResult]:
        self.predict_calls.append(kwargs)
        return [FakeResult(None) for _ in range(self._count)]


def make_bundle(
    tmp_path: Path,
    *,
    runtime: str = "ultralytics-8.4.125",
    output_adapter: str = "ultralytics-yolo-detect-v1",
    classifier: dict[str, str] | None = None,
) -> ModelBundle:
    manifest = ModelManifest.model_validate(
        {
            "schemaVersion": "animalguard-model-bundle-v1",
            "bundleVersion": "2026-08-24.7e4f5549",
            "runtime": runtime,
            "modelApiVersion": "animalguard-detection-v1",
            "outputAdapter": output_adapter,
            "detector": {
                "file": "wildlife_yolov8n_11class.pt",
                "version": "sha256:7e4f5549",
                "inputWidth": 640,
                "inputHeight": 640,
                "colorSpace": "RGB",
                "resizeMode": "letterbox",
                "confidenceThreshold": 0.6,
                "nmsThreshold": 0.7,
            },
            "classifier": classifier,
            "classMapFile": "classes.json",
            "unknownClassCode": "UNKNOWN",
        }
    )
    class_map = ClassMap.model_validate(
        {
            "schemaVersion": "animalguard-class-map-v1",
            "classes": (
                {"id": 0, "classCode": "MAGPIE"},
                {"id": 1, "classCode": "WILD_BOAR"},
            ),
            "unknownClassCode": "UNKNOWN",
        }
    )
    detector_path = tmp_path / "wildlife_yolov8n_11class.pt"
    classifier_path = tmp_path / "classifier.pt" if classifier else None
    return ModelBundle(
        directory=tmp_path,
        manifest=manifest,
        class_map=class_map,
        detector_path=detector_path,
        classifier_path=classifier_path,
    )


def test_projects_metadata_and_translates_predictions(tmp_path: Path) -> None:
    boxes = FakeBoxes(
        xyxy=[
            [-1.2, 2.8, 10.2, 9.1],
            [99.4, 49.2, 101.0, 50.0],
            [101.0, 0.0, 102.0, 1.0],
        ],
        confidence=[0.91, 0.82, 0.4],
        class_ids=[0.0, 1.0, 0.0],
    )
    model = FakeModel(boxes=boxes)
    bundle = make_bundle(tmp_path)
    loaded_paths: list[Path] = []

    def model_factory(path: Path) -> FakeModel:
        loaded_paths.append(path)
        return model

    engine = UltralyticsInference(bundle, model_factory=model_factory)
    image = Image.new("RGB", (100, 50), "white")
    frame = DecodedFrame(image=image, width=100, height=50)

    detections = engine.analyze(frame)

    assert loaded_paths == [bundle.detector_path]
    assert engine.ready is True
    assert engine.metadata.mode == "model"
    assert engine.metadata.runtime == "ultralytics-8.4.125"
    assert engine.metadata.bundle_version == "2026-08-24.7e4f5549"
    assert engine.metadata.detector_version == "sha256:7e4f5549"
    assert engine.metadata.classifier_version is None
    assert model.predict_calls == [
        {
            "source": image,
            "conf": 0.6,
            "iou": 0.7,
            "imgsz": (640, 640),
            "max_det": 100,
            "verbose": False,
        }
    ]
    assert [detection.model_dump() for detection in detections] == [
        {
            "detectionId": "det-001",
            "trackId": None,
            "classCode": "MAGPIE",
            "detectionConfidence": 0.91,
            "classificationConfidence": None,
            "bbox": {"x": 0, "y": 2, "width": 11, "height": 8},
        },
        {
            "detectionId": "det-002",
            "trackId": None,
            "classCode": "WILD_BOAR",
            "detectionConfidence": 0.82,
            "classificationConfidence": None,
            "bbox": {"x": 99, "y": 49, "width": 1, "height": 1},
        },
    ]
    image.close()


def test_returns_empty_list_when_model_has_no_boxes(tmp_path: Path) -> None:
    model = FakeModel(boxes=None)
    engine = UltralyticsInference(
        make_bundle(tmp_path),
        model_factory=lambda path: model,
    )
    image = Image.new("RGB", (20, 10), "white")

    assert engine.analyze(DecodedFrame(image=image, width=20, height=10)) == []
    image.close()


def test_serializes_concurrent_model_access(tmp_path: Path) -> None:
    model = BlockingModel()
    engine = UltralyticsInference(
        make_bundle(tmp_path),
        model_factory=lambda path: model,
    )
    frames = [
        DecodedFrame(
            image=Image.new("RGB", (20, 10), "white"),
            width=20,
            height=10,
        )
        for _ in range(2)
    ]

    with ThreadPoolExecutor(max_workers=2) as executor:
        first = executor.submit(engine.analyze, frames[0])
        assert model.first_started.wait(timeout=1)
        second = executor.submit(engine.analyze, frames[1])
        assert not model.second_started.wait(timeout=0.2)
        model.release_first.set()
        assert first.result(timeout=1) == []
        assert second.result(timeout=1) == []

    assert model.second_started.is_set()
    for frame in frames:
        frame.image.close()


def test_caps_translated_detections_at_one_hundred(tmp_path: Path) -> None:
    boxes = FakeBoxes(
        xyxy=[[0.0, 0.0, 2.0, 2.0] for _ in range(101)],
        confidence=[0.9 for _ in range(101)],
        class_ids=[0.0 for _ in range(101)],
    )
    engine = UltralyticsInference(
        make_bundle(tmp_path),
        model_factory=lambda path: FakeModel(boxes=boxes),
    )
    frame = DecodedFrame(
        image=Image.new("RGB", (20, 10), "white"),
        width=20,
        height=10,
    )

    detections = engine.analyze(frame)

    assert len(detections) == 100
    assert detections[-1].detectionId == "det-100"
    frame.image.close()


@pytest.mark.parametrize(
    ("boxes", "message"),
    [
        (
            FakeBoxes(
                xyxy=[[float("nan"), 0.0, 2.0, 2.0]],
                confidence=[0.9],
                class_ids=[0.0],
            ),
            "bounding box",
        ),
        (
            FakeBoxes(
                xyxy=[[0.0, 0.0, 2.0, 2.0]],
                confidence=[float("inf")],
                class_ids=[0.0],
            ),
            "non-finite",
        ),
        (
            FakeBoxes(
                xyxy=[[0.0, 0.0, 2.0, 2.0]],
                confidence=[0.9],
                class_ids=[float("inf")],
            ),
            "non-finite",
        ),
        (
            FakeBoxes(
                xyxy=[[0.0, 0.0, 2.0, 2.0]],
                confidence=[0.9],
                class_ids=[0.5],
            ),
            "not an integer",
        ),
        (
            FakeBoxes(
                xyxy=[[0.0, 0.0, 2.0, 2.0]],
                confidence=[],
                class_ids=[0.0],
            ),
            "lengths do not match",
        ),
    ],
)
def test_rejects_invalid_detection_output(
    tmp_path: Path,
    boxes: FakeBoxes,
    message: str,
) -> None:
    engine = UltralyticsInference(
        make_bundle(tmp_path),
        model_factory=lambda path: FakeModel(boxes=boxes),
    )
    frame = DecodedFrame(
        image=Image.new("RGB", (20, 10), "white"),
        width=20,
        height=10,
    )

    with pytest.raises(UltralyticsInferenceError, match=message):
        engine.analyze(frame)

    assert engine.ready is False
    frame.image.close()


@pytest.mark.parametrize("result_count", [0, 2])
def test_rejects_unexpected_result_count(
    tmp_path: Path,
    result_count: int,
) -> None:
    engine = UltralyticsInference(
        make_bundle(tmp_path),
        model_factory=lambda path: ResultCountModel(result_count),
    )
    frame = DecodedFrame(
        image=Image.new("RGB", (20, 10), "white"),
        width=20,
        height=10,
    )

    with pytest.raises(UltralyticsInferenceError, match="result count"):
        engine.analyze(frame)

    assert engine.ready is False
    frame.image.close()


def test_runtime_failure_disables_engine_without_leaking_details(
    tmp_path: Path,
) -> None:
    engine = UltralyticsInference(
        make_bundle(tmp_path),
        model_factory=lambda path: FailingModel(),
    )
    frame = DecodedFrame(
        image=Image.new("RGB", (20, 10), "white"),
        width=20,
        height=10,
    )

    with pytest.raises(UltralyticsInferenceError, match="inference failed") as error:
        engine.analyze(frame)

    assert "secret model failure" not in str(error.value)
    assert engine.ready is False
    frame.image.close()


def test_close_makes_engine_unavailable(tmp_path: Path) -> None:
    engine = UltralyticsInference(
        make_bundle(tmp_path),
        model_factory=lambda path: FakeModel(),
    )
    frame = DecodedFrame(
        image=Image.new("RGB", (20, 10), "white"),
        width=20,
        height=10,
    )

    engine.close()
    engine.close()

    assert engine.ready is False
    with pytest.raises(UltralyticsInferenceError, match="not ready"):
        engine.analyze(frame)
    frame.image.close()


def test_rejects_non_detection_checkpoint(tmp_path: Path) -> None:
    with pytest.raises(UltralyticsInferenceError, match="detection model"):
        UltralyticsInference(
            make_bundle(tmp_path),
            model_factory=lambda path: FakeModel(task="segment"),
        )


def test_rejects_checkpoint_class_ids_that_do_not_match_map(
    tmp_path: Path,
) -> None:
    with pytest.raises(UltralyticsInferenceError, match="class ids"):
        UltralyticsInference(
            make_bundle(tmp_path),
            model_factory=lambda path: FakeModel(
                names={0: "magpie", 1: "wild_boar", 2: "roe_deer"}
            ),
        )


def test_rejects_checkpoint_class_names_that_do_not_match_map(
    tmp_path: Path,
) -> None:
    with pytest.raises(UltralyticsInferenceError, match="class names"):
        UltralyticsInference(
            make_bundle(tmp_path),
            model_factory=lambda path: FakeModel(
                names={0: "wild_boar", 1: "magpie"}
            ),
        )


@pytest.mark.parametrize(
    ("runtime", "output_adapter"),
    [
        ("wrong-runtime", "ultralytics-yolo-detect-v1"),
        ("ultralytics-8.4.125", "wrong-adapter"),
    ],
)
def test_rejects_unsupported_bundle_identifiers(
    tmp_path: Path,
    runtime: str,
    output_adapter: str,
) -> None:
    with pytest.raises(UltralyticsInferenceError, match="not supported"):
        UltralyticsInference(
            make_bundle(
                tmp_path,
                runtime=runtime,
                output_adapter=output_adapter,
            ),
            model_factory=lambda path: FakeModel(),
        )


def test_rejects_classifier_bundle(tmp_path: Path) -> None:
    with pytest.raises(UltralyticsInferenceError, match="classifier"):
        UltralyticsInference(
            make_bundle(
                tmp_path,
                classifier={"file": "classifier.pt", "version": "classifier-v1"},
            ),
            model_factory=lambda path: FakeModel(),
        )


def test_default_loader_uses_exact_supported_ultralytics_version(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    detector_path = tmp_path / "detector.pt"
    model = object()
    loaded_paths: list[str] = []

    def yolo_factory(path: str) -> object:
        loaded_paths.append(path)
        return model

    monkeypatch.setitem(
        sys.modules,
        "ultralytics",
        SimpleNamespace(__version__="8.4.125", YOLO=yolo_factory),
    )

    assert load_ultralytics_model(detector_path) is model
    assert loaded_paths == [str(detector_path)]


def test_default_loader_rejects_different_ultralytics_version(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    def unexpected_yolo(path: str) -> object:
        pytest.fail("checkpoint must not load with a different runtime version")

    monkeypatch.setitem(
        sys.modules,
        "ultralytics",
        SimpleNamespace(__version__="8.4.124", YOLO=unexpected_yolo),
    )

    with pytest.raises(UltralyticsInferenceError, match="version"):
        load_ultralytics_model(tmp_path / "detector.pt")
