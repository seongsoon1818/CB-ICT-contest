from dataclasses import dataclass
from pathlib import Path
from typing import Annotated, Literal, Self

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    ValidationError,
    model_validator,
)


MODEL_BUNDLE_SCHEMA_VERSION = "animalguard-model-bundle-v1"
CLASS_MAP_SCHEMA_VERSION = "animalguard-class-map-v1"
MODEL_API_VERSION = "animalguard-detection-v1"
CLASS_CODE_PATTERN = r"^[A-Z][A-Z0-9_]*$"

NonBlankString = Annotated[
    str,
    StringConstraints(strip_whitespace=True, min_length=1),
]
ClassCode = Annotated[
    str,
    StringConstraints(pattern=CLASS_CODE_PATTERN),
]


class ModelBundleError(ValueError):
    pass


class BundleContractModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
        strict=True,
    )


class DetectorManifest(BundleContractModel):
    file: NonBlankString
    version: NonBlankString
    input_width: int = Field(alias="inputWidth", gt=0)
    input_height: int = Field(alias="inputHeight", gt=0)
    color_space: Literal["RGB"] = Field(alias="colorSpace")
    resize_mode: Literal["letterbox", "stretch"] = Field(alias="resizeMode")
    confidence_threshold: float = Field(
        alias="confidenceThreshold",
        ge=0,
        le=1,
    )
    nms_threshold: float = Field(alias="nmsThreshold", ge=0, le=1)


class ClassifierManifest(BundleContractModel):
    file: NonBlankString
    version: NonBlankString


class ModelManifest(BundleContractModel):
    schema_version: Literal[MODEL_BUNDLE_SCHEMA_VERSION] = Field(
        alias="schemaVersion"
    )
    bundle_version: NonBlankString = Field(alias="bundleVersion")
    runtime: NonBlankString
    model_api_version: Literal[MODEL_API_VERSION] = Field(
        alias="modelApiVersion"
    )
    output_adapter: NonBlankString = Field(alias="outputAdapter")
    detector: DetectorManifest
    classifier: ClassifierManifest | None
    class_map_file: NonBlankString = Field(alias="classMapFile")
    unknown_class_code: ClassCode = Field(alias="unknownClassCode")


class ClassEntry(BundleContractModel):
    id: int = Field(ge=0)
    class_code: ClassCode = Field(alias="classCode")


class ClassMap(BundleContractModel):
    schema_version: Literal[CLASS_MAP_SCHEMA_VERSION] = Field(
        alias="schemaVersion"
    )
    classes: tuple[ClassEntry, ...] = Field(min_length=1)
    unknown_class_code: ClassCode = Field(alias="unknownClassCode")

    @model_validator(mode="after")
    def validate_unique_classes(self) -> Self:
        ids = [entry.id for entry in self.classes]
        if len(ids) != len(set(ids)):
            raise ValueError("class ids must be unique")
        codes = [entry.class_code for entry in self.classes]
        if len(codes) != len(set(codes)):
            raise ValueError("class codes must be unique")
        return self


@dataclass(frozen=True)
class ModelBundle:
    directory: Path
    manifest: ModelManifest
    class_map: ClassMap
    detector_path: Path
    classifier_path: Path | None


class ModelBundleLoader:
    def load(self, directory: Path) -> ModelBundle:
        try:
            bundle_directory = directory.resolve(strict=True)
        except OSError as error:
            raise ModelBundleError("model bundle directory does not exist") from error
        if not bundle_directory.is_dir():
            raise ModelBundleError("model bundle directory is not a directory")

        manifest_path = bundle_directory / "model-manifest.json"
        try:
            manifest = ModelManifest.model_validate_json(
                manifest_path.read_text(encoding="utf-8")
            )
        except (OSError, UnicodeError, ValidationError, ValueError) as error:
            raise ModelBundleError("model manifest is invalid") from error

        class_map_path = self._resolve_file(
            bundle_directory,
            manifest.class_map_file,
        )
        try:
            class_map = ClassMap.model_validate_json(
                class_map_path.read_text(encoding="utf-8")
            )
        except (OSError, UnicodeError, ValidationError, ValueError) as error:
            raise ModelBundleError("class map is invalid") from error
        if class_map.unknown_class_code != manifest.unknown_class_code:
            raise ModelBundleError(
                "manifest and class map unknownClassCode must match"
            )

        detector_path = self._resolve_file(
            bundle_directory,
            manifest.detector.file,
        )
        classifier_path = None
        if manifest.classifier is not None:
            classifier_path = self._resolve_file(
                bundle_directory,
                manifest.classifier.file,
            )

        return ModelBundle(
            directory=bundle_directory,
            manifest=manifest,
            class_map=class_map,
            detector_path=detector_path,
            classifier_path=classifier_path,
        )

    @staticmethod
    def _resolve_file(bundle_directory: Path, name: str) -> Path:
        relative_path = Path(name)
        if relative_path.is_absolute() or ".." in relative_path.parts:
            raise ModelBundleError("bundle file path is unsafe")

        resolved_path = (bundle_directory / relative_path).resolve()
        try:
            resolved_path.relative_to(bundle_directory)
        except ValueError as error:
            raise ModelBundleError("bundle file path escapes bundle") from error
        if not resolved_path.is_file():
            raise ModelBundleError("bundle file does not exist or is not regular")
        return resolved_path
