import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Response
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, selectinload

from . import models, schemas
from .database import Base, engine, get_db
from .seed import seed_if_empty


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(engine)
    seed_if_empty()
    yield


app = FastAPI(title="Campus BLE Tour Guide API", lifespan=lifespan)


def _reading_out(r: models.Reading | None) -> schemas.ReadingOut | None:
    if r is None:
        return None
    age = max(0, (int(time.time()) - r.measured_at) // 60)
    return schemas.ReadingOut(
        sensor_id=r.sensor_id,
        seq=r.seq,
        measured_at=r.measured_at,
        temperature=r.temperature,
        humidity=r.humidity,
        age_minutes=age,
    )


def _latest_reading(db: Session, sensor_id: int | None) -> models.Reading | None:
    if sensor_id is None:
        return None
    return db.scalars(
        select(models.Reading)
        .where(models.Reading.sensor_id == sensor_id)
        .order_by(models.Reading.measured_at.desc(), models.Reading.seq.desc())
        .limit(1)
    ).first()


def _spot_out(db: Session, s: models.Spot) -> schemas.SpotOut:
    return schemas.SpotOut(
        id=s.id,
        name=s.name,
        name_en=s.name_en,
        description=s.description,
        description_en=s.description_en,
        sensor_id=s.sensor_id,
        ble_name=s.ble_name,
        latitude=s.latitude,
        longitude=s.longitude,
        latest_reading=_reading_out(_latest_reading(db, s.sensor_id)),
    )


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/api/readings", response_model=schemas.ReadingResult, status_code=201)
def post_reading(body: schemas.ReadingIn, response: Response, db: Session = Depends(get_db)):
    """측정값 업로드. 같은 (sensor_id, seq)가 다시 오면 200 + duplicate (앱 재전송 대응)."""
    exists = db.scalars(
        select(models.Reading.id).where(
            models.Reading.sensor_id == body.sensor_id, models.Reading.seq == body.seq
        )
    ).first()
    if exists is not None:
        response.status_code = 200
        return schemas.ReadingResult(status="duplicate")

    db.add(models.Reading(**body.model_dump(), received_at=int(time.time())))
    try:
        db.commit()
    except IntegrityError:  # 동시 요청으로 같은 키가 먼저 들어간 경우
        db.rollback()
        response.status_code = 200
        return schemas.ReadingResult(status="duplicate")
    return schemas.ReadingResult(status="created")


@app.get("/api/spots", response_model=list[schemas.SpotOut])
def list_spots(db: Session = Depends(get_db)):
    spots = db.scalars(select(models.Spot).order_by(models.Spot.id)).all()
    return [_spot_out(db, s) for s in spots]


@app.get("/api/spots/{spot_id}", response_model=schemas.SpotOut)
def get_spot(spot_id: int, db: Session = Depends(get_db)):
    spot = db.get(models.Spot, spot_id)
    if spot is None:
        raise HTTPException(404, "spot not found")
    return _spot_out(db, spot)


@app.get("/api/courses", response_model=list[schemas.CourseOut])
def list_courses(db: Session = Depends(get_db)):
    courses = db.scalars(
        select(models.Course)
        .options(selectinload(models.Course.stops).selectinload(models.CourseSpot.spot))
        .order_by(models.Course.id)
    ).all()
    return [
        schemas.CourseOut(
            id=c.id,
            name=c.name,
            name_en=c.name_en,
            description=c.description,
            total_minutes=sum(cs.minutes for cs in c.stops),
            spots=[
                schemas.CourseSpotOut(
                    position=cs.position, minutes=cs.minutes, spot=_spot_out(db, cs.spot)
                )
                for cs in c.stops
            ],
        )
        for c in courses
    ]
