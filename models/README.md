# Models

이 디렉터리는 AnimalGuard 모델 bundle 계약을 정의합니다. 실제 서버 bundle은 다음 구조를 사용합니다.

```text
/opt/animalguard/models/releases/2026-08-24.7e4f5549/
├── model-manifest.json
├── classes.json
├── SHA256SUMS
└── wildlife_yolov8n_11class.pt
```

classifier가 실제 pipeline에 포함될 때만 같은 bundle에 `classifier.onnx` 또는 `classifier.pt`를 둡니다. 모델 바이너리와 가중치 파일은 GitHub에 커밋하지 않습니다.

## 저장소 계약 파일

- `model-manifest.schema.json`: bundle metadata, model API, 전처리 경계 계약
- `class-map.schema.json`: model class ID와 Detection Event `classCode` 매핑 계약
- `class-map.example.json`: 형식 설명용 예제이며 운영 class 목록이 아님
- `bundle-metadata/wildlife-yolov8n-11class/`: 전달받은 11-class checkpoint의 검증된 manifest, class map과 SHA-256

현재 구현이 지원하는 조합은 PyTorch checkpoint, `ultralytics-8.4.125`, RGB 640x640 letterbox 입력과 `ultralytics-yolo-detect-v1`입니다. NMS IoU `0.7`은 pinned runtime의 `DEFAULT_CFG.iou` 값이며 manifest에서 명시적으로 전달합니다. 다른 runtime, output adapter, detector task, class ID 집합 또는 대소문자를 제외하고 classCode와 일치하지 않는 checkpoint class name은 시작 시 거절합니다. 모델 바이너리는 전달·배포 경로에만 두며 저장소 metadata 디렉터리는 그 자체로 완전한 실행 bundle이 아닙니다.

## 제공된 11-class checkpoint

검증된 외부 파일명은 `wildlife_yolov8n_11class.pt`, SHA-256은 `7e4f5549f40b844f2156c31739894e1a8cbbd33f4ef59d6d3f0b25e555fc4572`입니다. 모델 내 ID 0~10은 `MALLARD`, `COMMON_RAVEN`, `TREE_SPARROW`, `MAGPIE`, `CHIPMUNK`, `RACCOON_DOG`, `WILD_BOAR`, `EURASIAN_RED_SQUIRREL`, `WATER_DEER`, `KOREAN_HARE`, `ROE_DEER` 순서입니다.

AI팀 package의 `risk_rules.json`, ROI, 별도 HTTP API와 SQLite history는 bundle 입력이 아닙니다. AI Server는 탐지만 전달하고 class별 위험도와 장치 명령은 기존 Backend 정책이 결정합니다.

`ultralytics==8.4.125` 설치 package metadata는 license를 AGPL-3.0으로 선언합니다. 운영 배포·배포물 제공 방식에 적합한지는 별도 license 검토 대상으로 남깁니다.

## Manifest v1

`model-manifest.json`은 다음을 포함합니다.

- `schemaVersion`: `animalguard-model-bundle-v1`
- `bundleVersion`: 배포 bundle 버전
- `runtime`: 확정된 runtime 식별자
- `modelApiVersion`: 현재 `animalguard-detection-v1`
- `outputAdapter`: 확정된 출력 변환 계약 식별자
- `detector`: 파일, model version, 입력 크기, RGB, resize mode, confidence/NMS threshold
- `classifier`: 없으면 명시적인 `null`, 있으면 파일과 version
- `classMapFile`: class map 상대경로
- `unknownClassCode`: 알 수 없는 class의 contract code

입력 크기는 양수, threshold는 0~1이어야 합니다. color space는 현재 `RGB`만, resize mode는 `letterbox` 또는 `stretch`만 허용합니다. normalization, dynamic shape, batch size는 실제 모델 요구가 확정되기 전까지 계약에 추가하지 않습니다.

## Class map v1

`classes.json`의 `schemaVersion`은 `animalguard-class-map-v1`입니다. 각 class는 0 이상의 정수 `id`와 `^[A-Z][A-Z0-9_]*$` 형식의 고유한 `classCode`를 가집니다. ID는 중복될 수 없지만 연속적일 필요는 없습니다. `unknownClassCode`는 manifest와 일치해야 합니다.

실제 classCode별 위험 점수는 Backend 정책입니다. 위험 점수를 model bundle에 넣지 않습니다.

## 파일 경계

manifest의 `detector.file`, `classifier.file`, `classMapFile`은 bundle 내부의 실제 regular file만 가리킬 수 있습니다. 절대경로, `..`, bundle 밖으로 resolve되는 symlink, 없는 파일과 디렉터리는 거절합니다. 공통 schema는 binary hash나 서명을 강제하지 않지만 제공된 checkpoint metadata에는 배치 전 대조할 `SHA256SUMS`를 포함합니다.

## 재시작 기반 교체

서버 배치는 release 디렉터리와 `current` symlink를 사용합니다.

```text
/opt/animalguard/models/
├── releases/
│   ├── v1/
│   └── v2/
└── current -> releases/v1
```

배포 절차:

1. 새 release 디렉터리에 `bundle-metadata/wildlife-yolov8n-11class/`의 세 파일을 복사합니다.
2. 신뢰된 전달 경로의 checkpoint를 manifest와 같은 `wildlife_yolov8n_11class.pt` 이름으로 복사합니다. PyTorch `.pt`는 pickle 기반 artifact이므로 출처와 SHA-256을 확인하지 않은 파일은 로드하지 않습니다.
3. release 디렉터리에서 `shasum -a 256 -c SHA256SUMS`로 checkpoint를 검증합니다.
4. manifest와 class map 계약 및 파일 존재를 검증합니다.
5. `current` symlink를 새 release로 교체합니다.
6. AI Server를 재시작합니다.
7. `/health/ready`가 새 `bundleVersion`·model version으로 200인지 확인합니다.
8. 샘플 JPEG로 `/api/v1/analyze` smoke test를 실행합니다.

AI Server 가상환경에서 bundle 계약과 파일 경계를 별도로 확인할 수 있습니다.

```bash
cd ai-server
python -c 'from pathlib import Path; from app.model_bundle import ModelBundleLoader; ModelBundleLoader().load(Path("/opt/animalguard/models/releases/v2")); print("bundle contract valid")'
```

Rollback 절차:

1. `current` symlink를 이전 release로 복구합니다.
2. AI Server를 재시작합니다.
3. `/health/ready`와 샘플 JPEG smoke test를 다시 확인합니다.

실행 중 hot reload는 없습니다. 모델은 프로세스 시작 시 한 번만 로드합니다. 실행 중인 bundle의 일부 파일을 덮어쓰지 말고 항상 새 release 디렉터리를 완성한 뒤 symlink를 전환합니다.

코드 수정 없이 교체 가능한 범위는 같은 runtime, model 입출력 규격, `modelApiVersion`, `outputAdapter`, Detection Event v1 계약을 준수하는 bundle로 제한합니다. ONNX↔PyTorch, 완전히 다른 출력 구조, detector-only↔detector+classifier, 탐지↔세그멘테이션, tensor 규격 또는 근본 전처리 변경은 model file 교체만으로 지원하지 않습니다.
