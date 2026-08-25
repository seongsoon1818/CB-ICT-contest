from io import BytesIO

from PIL import Image

from app.inference import MockInference, decode_jpeg


def make_cmyk_jpeg(width: int = 20, height: int = 10) -> bytes:
    buffer = BytesIO()
    Image.new("CMYK", (width, height), (0, 0, 0, 0)).save(
        buffer,
        format="JPEG",
    )
    return buffer.getvalue()


def test_decode_jpeg_returns_independent_rgb_pixels_and_dimensions() -> None:
    frame = decode_jpeg(make_cmyk_jpeg())

    try:
        assert frame.image.mode == "RGB"
        assert frame.image.getpixel((0, 0)) == (255, 255, 255)
        assert frame.width == 20
        assert frame.height == 10
    finally:
        frame.image.close()


def test_mock_inference_uses_frame_and_exposes_metadata() -> None:
    frame = decode_jpeg(make_cmyk_jpeg())
    engine = MockInference("detected")

    try:
        detections = engine.analyze(frame)

        assert detections[0].bbox.model_dump() == {
            "x": 5,
            "y": 2,
            "width": 10,
            "height": 5,
        }
        assert engine.ready is True
        assert engine.metadata.mode == "mock"
        assert engine.metadata.runtime == "mock"
        assert engine.metadata.detector_version == "mock-animal-detector-v1"
        assert engine.metadata.classifier_version is None
        assert engine.metadata.bundle_version is None
        engine.close()
    finally:
        frame.image.close()
