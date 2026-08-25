import warnings
from io import BytesIO

from PIL import Image, UnidentifiedImageError

from app.schemas import Bbox, Detection
from app.settings import MockResult


DETECTOR_VERSION = "mock-animal-detector-v1"
MOCK_CLASS_CODE = "MAGPIE"
MAX_IMAGE_PIXELS = 40_000_000


class InvalidJpegError(ValueError):
    pass


class FrameTooLargeError(ValueError):
    pass


def decode_jpeg(data: bytes) -> tuple[int, int]:
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", Image.DecompressionBombWarning)
            image = Image.open(BytesIO(data))
        with image:
            if image.format != "JPEG":
                raise InvalidJpegError("Frame is not a JPEG image")
            width, height = image.size
            if width * height > MAX_IMAGE_PIXELS:
                raise FrameTooLargeError(
                    f"Frame exceeds the {MAX_IMAGE_PIXELS} pixel limit"
                )
            image.load()
            return width, height
    except Image.DecompressionBombError as error:
        raise FrameTooLargeError(
            f"Frame exceeds the {MAX_IMAGE_PIXELS} pixel limit"
        ) from error
    except (UnidentifiedImageError, OSError) as error:
        raise InvalidJpegError("Frame is not a valid JPEG image") from error


class MockInference:
    def __init__(self, result: MockResult) -> None:
        self._result = result

    def analyze(self, width: int, height: int) -> list[Detection]:
        if self._result == "empty":
            return []

        bbox_width = max(1, width // 2)
        bbox_height = max(1, height // 2)
        bbox = Bbox(
            x=(width - bbox_width) // 2,
            y=(height - bbox_height) // 2,
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
