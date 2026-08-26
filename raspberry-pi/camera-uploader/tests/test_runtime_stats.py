from animalguard_camera.runtime_stats import RuntimeStats


def test_snapshot_reports_runtime_counters_rates_and_latest_age() -> None:
    stats = RuntimeStats(started_monotonic=10.0)
    stats.record_captured(captured_monotonic=10.25)
    stats.record_captured(captured_monotonic=10.5)
    stats.record_uploaded()
    stats.record_overwritten()
    stats.record_capture_error()
    stats.record_upload_client_error()
    stats.record_upload_transient_error()

    snapshot = stats.snapshot(now_monotonic=12.0)

    assert snapshot.captured == 2
    assert snapshot.uploaded == 1
    assert snapshot.overwritten == 1
    assert snapshot.capture_errors == 1
    assert snapshot.upload_client_errors == 1
    assert snapshot.upload_transient_errors == 1
    assert snapshot.effective_capture_fps == 1.0
    assert snapshot.effective_upload_fps == 0.5
    assert snapshot.latest_frame_age_ms == 1500.0


def test_snapshot_has_no_age_before_first_capture_and_avoids_zero_division() -> None:
    stats = RuntimeStats(started_monotonic=10.0)

    snapshot = stats.snapshot(now_monotonic=10.0)

    assert snapshot.effective_capture_fps == 0.0
    assert snapshot.effective_upload_fps == 0.0
    assert snapshot.latest_frame_age_ms is None
