from datetime import datetime

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile


app = FastAPI()
EXPECTED_TOKEN = "test1"


@app.post("/api/v1/frames")
async def receive_frame(
    image: UploadFile = File(...),
    device_id: str = Form(...),
    mode: str = Form(...),
    captured_at: str = Form(...),
    sequence: int = Form(...),
    x_device_token: str | None = Header(default=None),
):
    if x_device_token != EXPECTED_TOKEN:
        raise HTTPException(status_code=401, detail="invalid device token")
    if image.content_type != "image/jpeg":
        raise HTTPException(status_code=415, detail="JPEG image required")

    content = await image.read()
    if not content:
        raise HTTPException(status_code=400, detail="empty image")
    if len(content) > 2 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="image too large")

    print(
        {
            "received_at": datetime.now().isoformat(),
            "filename": image.filename,
            "bytes": len(content),
            "device_id": device_id,
            "mode": mode,
            "captured_at": captured_at,
            "sequence": sequence,
        }
    )
    return {"accepted": True, "frame_id": f"{device_id}-{sequence}"}