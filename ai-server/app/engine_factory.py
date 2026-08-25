from app.inference import InferenceEngine, MockInference
from app.model_bundle import ModelBundleLoader
from app.settings import Settings


class InferenceEngineLoadError(RuntimeError):
    pass


def create_inference_engine(settings: Settings) -> InferenceEngine:
    if settings.inference_mode == "mock":
        return MockInference(settings.mock_result)

    if settings.model_bundle_dir is None:
        raise InferenceEngineLoadError(
            "MODEL_BUNDLE_DIR is required when INFERENCE_MODE=model"
        )

    bundle = ModelBundleLoader().load(settings.model_bundle_dir)
    raise InferenceEngineLoadError(
        "No runtime adapter is implemented for "
        f"runtime={bundle.manifest.runtime} "
        f"outputAdapter={bundle.manifest.output_adapter}"
    )
