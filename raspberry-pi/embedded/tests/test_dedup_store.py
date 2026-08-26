from animalguard_embedded.dedup_store import DedupStore


def test_sqlite_store_persists_latest_ack_across_restart(tmp_path):
    database = tmp_path / "processed.db"
    acknowledged = {
        "commandId": "command-001",
        "deviceId": "pi-001",
        "status": "ACKNOWLEDGED",
        "acknowledgedAt": "2026-08-26T09:00:00Z",
    }
    executed = {
        "commandId": "command-001",
        "deviceId": "pi-001",
        "status": "EXECUTED",
        "executedAt": "2026-08-26T09:00:01Z",
    }

    first = DedupStore(database)
    assert first.reserve(
        "command-001",
        "pi-001",
        "ACKNOWLEDGED",
        acknowledged,
        acknowledged["acknowledgedAt"],
    ) is True
    assert first.reserve(
        "command-001",
        "pi-001",
        "ACKNOWLEDGED",
        acknowledged,
        acknowledged["acknowledgedAt"],
    ) is False
    first.update(
        "command-001",
        "EXECUTED",
        executed,
        executed["executedAt"],
    )
    first.close()

    second = DedupStore(database)
    stored = second.get("command-001")
    assert stored is not None
    assert stored.command_id == "command-001"
    assert stored.device_id == "pi-001"
    assert stored.status == "EXECUTED"
    assert stored.ack_payload == executed
    assert stored.processed_at == "2026-08-26T09:00:01Z"
    second.close()
