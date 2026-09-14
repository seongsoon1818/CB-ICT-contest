import math
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from threading import Lock
from typing import Any

from app.inference import DecodedFrame, InferenceMetadata
from app.model_bundle import ModelBundle
from app.schemas import Bbox, Detection


ULTRALYTICS_VERSION = "8.4.125"
ULTRALYTICS_RUNTIME = f"ultralytics-{ULTRALYTICS_VERSION}"
ULTRALYTICS_OUTPUT_ADAPTER = "ultralytics-yolo-detect-v1"
MAX_DETECTIONS = 100

ModelFactory = Callable[[Path], Any]


class UltralyticsInferenceError(RuntimeError):
    pass


def load_ultralytics_model(detector_path: Path) -> Any:
    try:
        import ultralytics
    except ImportError as error:
        raise UltralyticsInferenceError(
            "Ultralytics runtime is not installed"
        ) from error

    installed_version = getattr(ultralytics, "__version__", None)
    if installed_version != ULTRALYTICS_VERSION:
        raise UltralyticsInferenceError(
            "Installed Ultralytics version does not match the supported runtime"
        )

    try:
        return ultralytics.YOLO(str(detector_path))
    except Exception as error:
        raise UltralyticsInferenceError(
            "Ultralytics detector could not be loaded"
        ) from error


class UltralyticsInference:
    def __init__(
        self,
        bundle: ModelBundle,
        *,
        model_factory: ModelFactory = load_ultralytics_model,
    ) -> None:
        manifest = bundle.manifest
        if (
            manifest.runtime != ULTRALYTICS_RUNTIME
            or manifest.output_adapter != ULTRALYTICS_OUTPUT_ADAPTER
        ):
            raise UltralyticsInferenceError(
                "Model bundle runtime or output adapter is not supported"
            )
        if manifest.classifier is not None or bundle.classifier_path is not None:
            raise UltralyticsInferenceError(
                "Ultralytics detector adapter does not support a classifier"
            )

        try:
            model = model_factory(bundle.detector_path)
        except UltralyticsInferenceError:
            raise
        except Exception as error:
            raise UltralyticsInferenceError(
                "Ultralytics detector could not be loaded"
            ) from error
        if getattr(model, "task", None) != "detect":
            raise UltralyticsInferenceError(
                "Ultralytics checkpoint is not a detection model"
            )

        class_codes_by_id = {
            entry.id: entry.class_code for entry in bundle.class_map.classes
        }
        model_class_names = self._model_class_names(model)
        if set(model_class_names) != set(class_codes_by_id):
            raise UltralyticsInferenceError(
                "Ultralytics checkpoint class ids do not match the class map"
            )
        if any(
            model_class_names[class_id].strip().upper() != class_code
            for class_id, class_code in class_codes_by_id.items()
        ):
            raise UltralyticsInferenceError(
                "Ultralytics checkpoint class names do not match the class map"
            )

        self._metadata = InferenceMetadata(
            mode="model",
            runtime=manifest.runtime,
            bundle_version=manifest.bundle_version,
            detector_version=manifest.detector.version,
            classifier_version=None,
        )
        self._confidence_threshold = manifest.detector.confidence_threshold
        self._nms_threshold = manifest.detector.nms_threshold
        self._input_size = (
            manifest.detector.input_height,
            manifest.detector.input_width,
        )
        self._class_codes_by_id = class_codes_by_id
        self._lock = Lock()
        self._model: Any | None = model

    @property
    def metadata(self) -> InferenceMetadata:
        return self._metadata

    @property
    def ready(self) -> bool:
        with self._lock:
            return self._model is not None

    def analyze(self, frame: DecodedFrame) -> list[Detection]:
        if frame.image.mode != "RGB":
            raise UltralyticsInferenceError("Ultralytics input must use RGB pixels")

        with self._lock:
            if self._model is None:
                raise UltralyticsInferenceError(
                    "Ultralytics inference engine is not ready"
                )
            try:
                results = list(
                    self._model.predict(
                        source=frame.image,
                        conf=self._confidence_threshold,
                        iou=self._nms_threshold,
                        imgsz=self._input_size,
                        max_det=MAX_DETECTIONS,
                        verbose=False,
                    )
                )
            except Exception as error:
                raise UltralyticsInferenceError(
                    "Ultralytics prediction failed"
                ) from error
            if len(results) != 1:
                raise UltralyticsInferenceError(
                    "Ultralytics returned an unexpected result count"
                )
            return self._translate_result(results[0], frame)

    def close(self) -> None:
        with self._lock:
            self._model = None

    @staticmethod
    def _model_class_names(model: Any) -> dict[int, str]:
        names = getattr(model, "names", None)
        if isinstance(names, Mapping):
            try:
                class_names = {
                    int(class_id): class_name
                    for class_id, class_name in names.items()
                }
            except (TypeError, ValueError) as error:
                raise UltralyticsInferenceError(
                    "Ultralytics checkpoint class ids are invalid"
                ) from error
            if len(class_names) != len(names):
                raise UltralyticsInferenceError(
                    "Ultralytics checkpoint class ids are invalid"
                )
        elif isinstance(names, Sequence) and not isinstance(names, (str, bytes)):
            class_names = dict(enumerate(names))
        else:
            raise UltralyticsInferenceError(
                "Ultralytics checkpoint class ids are unavailable"
            )
        if not all(isinstance(name, str) for name in class_names.values()):
            raise UltralyticsInferenceError(
                "Ultralytics checkpoint class names are invalid"
            )
        return class_names

    def _translate_result(
        self,
        result: Any,
        frame: DecodedFrame,
    ) -> list[Detection]:
        boxes = getattr(result, "boxes", None)
        if boxes is None:
            return []

        try:
            coordinates = boxes.xyxy.tolist()
            confidences = boxes.conf.tolist()
            class_ids = boxes.cls.tolist()
        except (AttributeError, TypeError, ValueError, OverflowError) as error:
            raise UltralyticsInferenceError(
                "Ultralytics detection output is invalid"
            ) from error
        try:
            output_lengths_match = (
                len(coordinates) == len(confidences) == len(class_ids)
            )
        except TypeError as error:
            raise UltralyticsInferenceError(
                "Ultralytics detection output is invalid"
            ) from error
        if not output_lengths_match:
            raise UltralyticsInferenceError(
                "Ultralytics detection output lengths do not match"
            )

        candidates: list[tuple[float, str, Bbox]] = []
        for raw_box, raw_confidence, raw_class_id in zip(
            coordinates,
            confidences,
            class_ids,
            strict=True,
        ):
            try:
                confidence = float(raw_confidence)
                class_number = float(raw_class_id)
            except (TypeError, ValueError, OverflowError) as error:
                raise UltralyticsInferenceError(
                    "Ultralytics detection confidence or class id is invalid"
                ) from error
            if not math.isfinite(confidence) or not 0 <= confidence <= 1:
                raise UltralyticsInferenceError(
                    "Ultralytics detection confidence is outside 0 through 1"
                )
            if not math.isfinite(class_number):
                raise UltralyticsInferenceError(
                    "Ultralytics detection class id is not finite"
                )
            class_id = int(class_number)
            if class_number != class_id:
                raise UltralyticsInferenceError(
                    "Ultralytics detection class id is not an integer"
                )
            try:
                class_code = self._class_codes_by_id[class_id]
            except KeyError as error:
                raise UltralyticsInferenceError(
                    "Ultralytics detection class id is not present in the class map"
                ) from error

            bbox = self._to_bbox(raw_box, frame.width, frame.height)
            if bbox is None:
                continue
            candidates.append((confidence, class_code, bbox))

        candidates.sort(key=lambda candidate: candidate[0], reverse=True)
        return [
            Detection(
                detectionId=f"det-{index:03d}",
                trackId=None,
                classCode=class_code,
                detectionConfidence=confidence,
                classificationConfidence=None,
                bbox=bbox,
            )
            for index, (confidence, class_code, bbox) in enumerate(
                candidates[:MAX_DETECTIONS],
                start=1,
            )
        ]

    @staticmethod
    def _to_bbox(
        raw_box: Sequence[float],
        frame_width: int,
        frame_height: int,
    ) -> Bbox | None:
        try:
            coordinate_count = len(raw_box)
        except TypeError as error:
            raise UltralyticsInferenceError(
                "Ultralytics bounding box is invalid"
            ) from error
        if coordinate_count != 4:
            raise UltralyticsInferenceError(
                "Ultralytics bounding box does not have four coordinates"
            )
        try:
            coordinates = [float(value) for value in raw_box]
        except (TypeError, ValueError, OverflowError) as error:
            raise UltralyticsInferenceError(
                "Ultralytics bounding box contains an invalid coordinate"
            ) from error
        if not all(math.isfinite(value) for value in coordinates):
            raise UltralyticsInferenceError(
                "Ultralytics bounding box contains a non-finite value"
            )

        x_min = max(0, min(frame_width, math.floor(coordinates[0])))
        y_min = max(0, min(frame_height, math.floor(coordinates[1])))
        x_max = max(0, min(frame_width, math.ceil(coordinates[2])))
        y_max = max(0, min(frame_height, math.ceil(coordinates[3])))
        width = x_max - x_min
        height = y_max - y_min
        if width <= 0 or height <= 0:
            return None
        return Bbox(x=x_min, y=y_min, width=width, height=height)
