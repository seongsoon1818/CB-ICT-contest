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


def test_snapshot_rates_cover_only_the_latest_interval() -> None:
    stats = RuntimeStats(started_monotonic=0.0)
    for sequence in range(300):
        stats.record_captured(captured_monotonic=sequence / 30)
    for _ in range(100):
        stats.record_uploaded()

    first = stats.snapshot(now_monotonic=10.0)

    for sequence in range(20):
        stats.record_captured(captured_monotonic=10.0 + sequence / 0.4)
    for _ in range(5):
        stats.record_uploaded()

    second = stats.snapshot(now_monotonic=60.0)

    assert first.effective_capture_fps == 30.0
    assert first.effective_upload_fps == 10.0
    assert second.captured == 320
    assert second.uploaded == 105
    assert second.effective_capture_fps == 0.4
    assert second.effective_upload_fps == 0.1


def test_zero_elapsed_snapshot_does_not_discard_rate_samples() -> None:
    stats = RuntimeStats(started_monotonic=10.0)
    stats.record_captured(captured_monotonic=10.0)
    stats.record_uploaded()

    same_time = stats.snapshot(now_monotonic=10.0)
    later = stats.snapshot(now_monotonic=11.0)

    assert same_time.effective_capture_fps == 0.0
    assert same_time.effective_upload_fps == 0.0
    assert later.effective_capture_fps == 1.0
    assert later.effective_upload_fps == 1.0
