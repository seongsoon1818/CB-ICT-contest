import json
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from app.model_bundle import ModelBundleError, ModelBundleLoader


MODELS_DIRECTORY = Path(__file__).resolve().parents[2] / "models"


def valid_manifest(**overrides: Any) -> dict[str, Any]:
    manifest: dict[str, Any] = {
        "schemaVersion": "animalguard-model-bundle-v1",
        "bundleVersion": "2026-08-25.1",
        "runtime": "future-runtime",
        "modelApiVersion": "animalguard-detection-v1",
        "outputAdapter": "future-detect-v1",
        "detector": {
            "file": "detector.bin",
            "version": "animal-detector-v1",
            "inputWidth": 640,
            "inputHeight": 640,
            "colorSpace": "RGB",
            "resizeMode": "letterbox",
            "confidenceThreshold": 0.25,
            "nmsThreshold": 0.45,
        },
        "classifier": None,
        "classMapFile": "classes.json",
        "unknownClassCode": "UNKNOWN",
    }
    manifest.update(overrides)
    return manifest


def valid_class_map(**overrides: Any) -> dict[str, Any]:
    class_map: dict[str, Any] = {
        "schemaVersion": "animalguard-class-map-v1",
        "classes": [
            {"id": 0, "classCode": "MAGPIE"},
            {"id": 4, "classCode": "WILD_BOAR"},
        ],
        "unknownClassCode": "UNKNOWN",
    }
    class_map.update(overrides)
    return class_map


def write_bundle(
    directory: Path,
    *,
    manifest: dict[str, Any] | None = None,
    class_map: dict[str, Any] | None = None,
) -> Path:
    directory.mkdir()
    manifest_value = manifest or valid_manifest()
    class_map_value = class_map or valid_class_map()
    (directory / "model-manifest.json").write_text(
        json.dumps(manifest_value),
        encoding="utf-8",
    )
    class_map_name = Path(manifest_value.get("classMapFile", "classes.json"))
    if not class_map_name.is_absolute() and ".." not in class_map_name.parts:
        (directory / class_map_name).write_text(
            json.dumps(class_map_value),
            encoding="utf-8",
        )
    detector = manifest_value.get("detector")
    if isinstance(detector, dict):
        detector_name = Path(detector.get("file", "detector.bin"))
        if not detector_name.is_absolute() and ".." not in detector_name.parts:
            (directory / detector_name).write_text(
                "dummy detector",
                encoding="utf-8",
            )
    classifier = manifest_value.get("classifier")
    if isinstance(classifier, dict):
        classifier_name = Path(classifier.get("file", "classifier.bin"))
        if not classifier_name.is_absolute() and ".." not in classifier_name.parts:
            (directory / classifier_name).write_text(
                "dummy classifier",
                encoding="utf-8",
            )
    return directory


def test_loads_valid_bundle_with_non_contiguous_class_ids(tmp_path: Path) -> None:
    bundle_dir = write_bundle(tmp_path / "bundle")

    bundle = ModelBundleLoader().load(bundle_dir)

    assert bundle.directory == bundle_dir.resolve()
    assert bundle.manifest.runtime == "future-runtime"
    assert bundle.manifest.classifier is None
    assert bundle.class_map.classes[1].id == 4
    assert bundle.detector_path == (bundle_dir / "detector.bin").resolve()
    assert bundle.classifier_path is None


@pytest.mark.parametrize(
    "manifest",
    [
        {key: value for key, value in valid_manifest().items() if key != "runtime"},
        valid_manifest(schemaVersion="wrong"),
        valid_manifest(modelApiVersion="unsupported"),
        valid_manifest(
            detector={**valid_manifest()["detector"], "inputWidth": 0}
        ),
        valid_manifest(
            detector={**valid_manifest()["detector"], "inputHeight": -1}
        ),
        valid_manifest(
            detector={
                **valid_manifest()["detector"],
                "confidenceThreshold": 1.1,
            }
        ),
        valid_manifest(
            detector={**valid_manifest()["detector"], "nmsThreshold": -0.1}
        ),
        valid_manifest(
            detector={**valid_manifest()["detector"], "colorSpace": "BGR"}
        ),
        valid_manifest(
            detector={**valid_manifest()["detector"], "resizeMode": "crop"}
        ),
        valid_manifest(
            detector={**valid_manifest()["detector"], "inputWidth": "640"}
        ),
    ],
)
def test_rejects_invalid_manifest(
    tmp_path: Path,
    manifest: dict[str, Any],
) -> None:
    bundle_dir = write_bundle(tmp_path / "bundle", manifest=manifest)

    with pytest.raises(ModelBundleError, match="manifest"):
        ModelBundleLoader().load(bundle_dir)


@pytest.mark.parametrize(
    "class_map",
    [
        valid_class_map(classes=[]),
        valid_class_map(
            classes=[
                {"id": 0, "classCode": "MAGPIE"},
                {"id": 0, "classCode": "WILD_BOAR"},
            ]
        ),
        valid_class_map(
            classes=[
                {"id": 0, "classCode": "MAGPIE"},
                {"id": 1, "classCode": "MAGPIE"},
            ]
        ),
        valid_class_map(classes=[{"id": 0, "classCode": "wild-boar"}]),
        valid_class_map(classes=[{"id": "0", "classCode": "MAGPIE"}]),
    ],
)
def test_rejects_invalid_class_map(
    tmp_path: Path,
    class_map: dict[str, Any],
) -> None:
    bundle_dir = write_bundle(tmp_path / "bundle", class_map=class_map)

    with pytest.raises(ModelBundleError, match="class map"):
        ModelBundleLoader().load(bundle_dir)


def test_loads_optional_classifier(tmp_path: Path) -> None:
    manifest = valid_manifest(
        classifier={"file": "classifier.bin", "version": "classifier-v1"}
    )
    bundle_dir = write_bundle(tmp_path / "bundle", manifest=manifest)

    bundle = ModelBundleLoader().load(bundle_dir)

    assert bundle.classifier_path == (bundle_dir / "classifier.bin").resolve()
    assert bundle.manifest.classifier is not None
    assert bundle.manifest.classifier.version == "classifier-v1"


@pytest.mark.parametrize(
    "field",
    ["classMapFile", "detector.file", "classifier.file"],
)
@pytest.mark.parametrize("unsafe_name", ["../outside.bin", "/tmp/outside.bin"])
def test_rejects_traversal_and_absolute_paths(
    tmp_path: Path,
    field: str,
    unsafe_name: str,
) -> None:
    manifest = valid_manifest()
    if field == "classMapFile":
        manifest["classMapFile"] = unsafe_name
    elif field == "detector.file":
        manifest["detector"] = {
            **manifest["detector"],
            "file": unsafe_name,
        }
    else:
        manifest["classifier"] = {
            "file": unsafe_name,
            "version": "classifier-v1",
        }
    bundle_dir = write_bundle(tmp_path / "bundle", manifest=manifest)

    with pytest.raises(ModelBundleError, match="path"):
        ModelBundleLoader().load(bundle_dir)


def manifest_for_file_target(target: str) -> dict[str, Any]:
    manifest = valid_manifest()
    if target == "classifier":
        manifest["classifier"] = {
            "file": "classifier.bin",
            "version": "classifier-v1",
        }
    return manifest


def bundle_file_path(bundle_dir: Path, target: str) -> Path:
    return bundle_dir / {
        "detector": "detector.bin",
        "classifier": "classifier.bin",
        "class_map": "classes.json",
    }[target]


@pytest.mark.parametrize("target", ["detector", "classifier", "class_map"])
def test_rejects_symlink_resolving_outside_bundle(
    tmp_path: Path,
    target: str,
) -> None:
    outside = tmp_path / "outside.bin"
    outside.write_text("outside", encoding="utf-8")
    bundle_dir = write_bundle(
        tmp_path / "bundle",
        manifest=manifest_for_file_target(target),
    )
    target_path = bundle_file_path(bundle_dir, target)
    target_path.unlink()
    target_path.symlink_to(outside)

    with pytest.raises(ModelBundleError, match="path"):
        ModelBundleLoader().load(bundle_dir)


@pytest.mark.parametrize("target", ["detector", "classifier", "class_map"])
def test_rejects_missing_bundle_file(tmp_path: Path, target: str) -> None:
    bundle_dir = write_bundle(
        tmp_path / "bundle",
        manifest=manifest_for_file_target(target),
    )
    bundle_file_path(bundle_dir, target).unlink()

    with pytest.raises(ModelBundleError, match="file"):
        ModelBundleLoader().load(bundle_dir)


@pytest.mark.parametrize("target", ["detector", "classifier", "class_map"])
def test_rejects_directory_instead_of_bundle_file(
    tmp_path: Path,
    target: str,
) -> None:
    bundle_dir = write_bundle(
        tmp_path / "bundle",
        manifest=manifest_for_file_target(target),
    )
    target_path = bundle_file_path(bundle_dir, target)
    target_path.unlink()
    target_path.mkdir()

    with pytest.raises(ModelBundleError, match="file"):
        ModelBundleLoader().load(bundle_dir)


def test_rejects_unknown_class_code_mismatch(tmp_path: Path) -> None:
    bundle_dir = write_bundle(
        tmp_path / "bundle",
        class_map=valid_class_map(unknownClassCode="UNMAPPED"),
    )

    with pytest.raises(ModelBundleError, match="unknownClassCode"):
        ModelBundleLoader().load(bundle_dir)


def test_repository_contract_schemas_are_valid_and_accept_examples() -> None:
    manifest_schema = json.loads(
        (MODELS_DIRECTORY / "model-manifest.schema.json").read_text(
            encoding="utf-8"
        )
    )
    class_map_schema = json.loads(
        (MODELS_DIRECTORY / "class-map.schema.json").read_text(
            encoding="utf-8"
        )
    )
    class_map_example = json.loads(
        (MODELS_DIRECTORY / "class-map.example.json").read_text(
            encoding="utf-8"
        )
    )

    Draft202012Validator.check_schema(manifest_schema)
    Draft202012Validator.check_schema(class_map_schema)
    assert list(Draft202012Validator(manifest_schema).iter_errors(valid_manifest())) == []
    assert list(
        Draft202012Validator(class_map_schema).iter_errors(class_map_example)
    ) == []
