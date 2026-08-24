# AI Server

이 디렉터리는 향후 Python 3.11과 FastAPI 기반 AI 추론 서버의 경계입니다. 유해동물 탐지·분류, 이미지 처리, Detection Event v1 생성이 이 구성요소의 책임입니다.

현재는 실행 코드와 모델 로딩을 추가하지 않고 AI Server와 Backend 사이의 `docs/contracts/detection-event-v1.md` 계약만 정의합니다. detector만 사용하는 모델은 `classifierVersion`을 `null`로 보냅니다.
