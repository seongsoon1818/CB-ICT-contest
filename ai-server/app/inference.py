from io import BytesIO

from PIL import Image, UnidentifiedImageError

from app.schemas import Bbox, Detection
from app.settings import MockResult


DETECTOR_VERSION = "mock-animal-detector-v1"
MOCK_CLASS_CODE = "MAGPIE"


class InvalidJpegError(ValueError):
    pass


def decode_jpeg(data: bytes) -> tuple[int, int]:
    try:
        with Image.open(BytesIO(data)) as image:
            image.load()
            if image.format != "JPEG":
                raise InvalidJpegError("Frame is not a JPEG image")
            return image.size
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as error:
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
