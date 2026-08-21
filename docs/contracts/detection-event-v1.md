# Detection Event v1 Contract

## Endpoint

AI Server는 다음 Backend endpoint로 이미지 단위 탐지 결과를 보냅니다.

POST /api/v1/detection/events

JSON Schema: detection-event-v1.schema.json

## Request example

~~~json
{
  "eventId": "15356786-9588-4db4-a0fe-f8acd6300868",
  "cameraId": "cam-001",
  "capturedAt": "2026-08-21T07:00:00Z",
  "image": {
    "width": 1920,
    "height": 1080
  },
  "model": {
    "detectorVersion": "bird-detector-v1",
    "classifierVersion": "bird-classifier-v1"
  },
  "birds": [
    {
      "detectionId": "det-001",
      "trackId": null,
      "speciesCode": "MAGPIE",
      "detectionConfidence": 0.95,
      "classificationConfidence": 0.92,
      "bbox": {
        "x": 100,
        "y": 200,
        "width": 50,
        "height": 60
      }
    }
  ]
}
~~~

## Field contract

| Field | Contract |
| --- | --- |
| eventId | UUID. Backend uses it as the idempotency and duplicate-reception key. |
| cameraId | Stable camera identifier. |
| capturedAt | ISO 8601 / RFC 3339 date-time with an explicit timezone. |
| image.width, image.height | Positive image pixel dimensions. |
| model.detectorVersion | Detector model version that produced the detections. |
| model.classifierVersion | Classifier model version that produced the species result. |
| birds | Zero or more bird detections. A frame with no bird uses birds: []. |
| detectionId | Identifier unique within this event. |
| trackId | Nullable in MVP. It is not used to derive dwell time in the single-image MVP. |
| speciesCode | Species code. Use UNKNOWN when the species cannot be identified. |
| detectionConfidence | Object detector confidence, independent from classificationConfidence. |
| classificationConfidence | Bird species classifier confidence, independent from detectionConfidence. |
| bbox | Required x, y, width, height rectangle in image pixels. |
| bbox.x, bbox.y | Non-negative pixel coordinates. The origin is the top-left corner. |
| bbox.width, bbox.height | Positive pixel dimensions. |

## Backend-owned decisions

- AI does not send insideField.
- Backend calculates insideField from the camera-specific ROI and the bbox center point.
- The MVP accepts trackId as nullable and does not calculate dwell time from a single image.
- Unknown species are represented as UNKNOWN, not an omitted or free-form null value.
- The event contains metadata and detections only; model files and Base64 image data are not included.
- eventId deduplication is applied before creating a second stored detection event. Duplicate requests must be safely idempotent.

## Time and coordinate rules

- Every time value includes a timezone and uses ISO 8601 / RFC 3339 JSON date-time notation.
- bbox coordinates are integer image pixels with the top-left pixel coordinate system.
- The contract requires all four bbox fields. It does not imply that a bbox may be cropped or normalized.
- Image dimensions are positive integers. Coordinates are non-negative integers; physical bounds checking against image dimensions is a Backend validation concern.

## Empty event behavior

A valid image with no detected birds is a normal event and must use birds: []. It is not an error and is still eligible for event storage and later audit.

## Compatibility and validation

The JSON Schema is the machine-readable source for required structure and primitive constraints. This prose document defines the semantic rules owned by Backend and the deferred implementation decisions. Producers and consumers must update the contract documents together when v1 changes.
