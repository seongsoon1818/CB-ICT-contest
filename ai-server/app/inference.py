import warnings
from dataclasses import dataclass
from io import BytesIO
from typing import Protocol

from PIL import Image, UnidentifiedImageError

from app.schemas import Bbox, Detection
from app.settings import MockResult


DETECTOR_VERSION = "mock-animal-detector-v1"
MOCK_CLASS_CODE = "MAGPIE"
MAX_IMAGE_PIXELS = 40_000_000


@dataclass
class DecodedFrame:
    image: Image.Image
    width: int
    height: int


@dataclass(frozen=True)
class InferenceMetadata:
    mode: str
    detector_version: str
    classifier_version: str | None
    bundle_version: str | None
    runtime: str


class InferenceEngine(Protocol):
    @property
    def metadata(self) -> InferenceMetadata: ...

    @property
    def ready(self) -> bool: ...

    def analyze(self, frame: DecodedFrame) -> list[Detection]: ...

    def close(self) -> None: ...


class InvalidJpegError(ValueError):
    pass


class FrameTooLargeError(ValueError):
    pass


def decode_jpeg(data: bytes) -> DecodedFrame:
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", Image.DecompressionBombWarning)
            with BytesIO(data) as buffer, Image.open(buffer) as source:
                if source.format != "JPEG":
                    raise InvalidJpegError("Frame is not a JPEG image")
                width, height = source.size
                if width * height > MAX_IMAGE_PIXELS:
                    raise FrameTooLargeError(
                        f"Frame exceeds the {MAX_IMAGE_PIXELS} pixel limit"
                    )
                source.load()
                image = source.convert("RGB")
                image.load()
            if image.mode != "RGB":
                image.close()
                raise InvalidJpegError("Frame is not a JPEG image")
            return DecodedFrame(image=image, width=width, height=height)
    except Image.DecompressionBombError as error:
        raise FrameTooLargeError(
            f"Frame exceeds the {MAX_IMAGE_PIXELS} pixel limit"
        ) from error
    except (UnidentifiedImageError, OSError) as error:
        raise InvalidJpegError("Frame is not a valid JPEG image") from error


class MockInference:
    def __init__(self, result: MockResult) -> None:
        self._result = result

    @property
    def metadata(self) -> InferenceMetadata:
        return InferenceMetadata(
            mode="mock",
            runtime="mock",
            detector_version=DETECTOR_VERSION,
            classifier_version=None,
            bundle_version=None,
        )

    @property
    def ready(self) -> bool:
        return True

    def analyze(self, frame: DecodedFrame) -> list[Detection]:
        if self._result == "empty":
            return []

        bbox_width = max(1, frame.width // 2)
        bbox_height = max(1, frame.height // 2)
        bbox = Bbox(
            x=(frame.width - bbox_width) // 2,
            y=(frame.height - bbox_height) // 2,
            width=bbox_width,
            height=bbox_height,
        )
        return [
            Detection(
                detectionId="det-001",
                trackId=None,
                classCode=MOCK_CLASS_CODE,
                detectionConfidence=0.95,
                classificationConfidence=None,
                bbox=bbox,
            )
        ]

    def close(self) -> None:
        pass
