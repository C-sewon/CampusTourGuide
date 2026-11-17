"""환경 맞춤 코스 추천: 기온이 너무 덥거나 추우면 짧은 코스를, 활동하기 좋으면 가장 긴 코스를 추천한다.

DB 에 의존하지 않는 순수 함수만 둔다. 장소별 현재 기온은 호출하는 쪽(main.py)이 모아서 넘긴다.
"""
from dataclasses import dataclass

from pydantic import BaseModel, Field

COLD, COMFORTABLE, HOT, UNKNOWN = "cold", "comfortable", "hot", "unknown"


def classify(temperature: float | None, cold_max: float, hot_min: float) -> str:
    """기온을 춥다(cold), 쾌적하다(comfortable), 덥다(hot), 알 수 없다(unknown)로 나눈다.

    cold_max 이하면 춥다, hot_min 이상이면 덥다. 경계값은 춥다/덥다에 포함된다.
    """
    if temperature is None:
        return UNKNOWN
    if temperature <= cold_max:
        return COLD
    if temperature >= hot_min:
        return HOT
    return COMFORTABLE


@dataclass
class CourseInfo:
    id: int
    name: str
    total_minutes: int
    spot_ids: list[int]


class CourseChoiceOut(BaseModel):
    id: int
    name: str
    total_minutes: int
    average_temperature: float | None = Field(
        description="이 코스에 포함된 장소들의 현재 기온 평균(℃). 기온을 아는 장소가 없으면 null"
    )
    recommended: bool


class ThresholdsOut(BaseModel):
    cold_max: int = Field(description="이 기온(℃) 이하면 춥다고 본다")
    hot_min: int = Field(description="이 기온(℃) 이상이면 덥다고 본다")


class RecommendationOut(BaseModel):
    condition: str = Field(description="cold(춥다) | comfortable(쾌적) | hot(덥다) | unknown(기온을 알 수 없음)")
    temperature: float | None = Field(
        description="판단에 쓴 기온(℃). 코스에 포함된 모든 장소의 현재 기온 평균. 알 수 없으면 null"
    )
    sample_count: int = Field(description="기온 평균에 쓴 장소 수")
    thresholds: ThresholdsOut
    recommended_course_id: int | None = Field(description="추천 코스. 코스가 없으면 null")
    recommended_course_name: str | None
    reason: str = Field(description="추천 이유. 화면에 그대로 보여줄 수 있는 문장")
    courses: list[CourseChoiceOut]


def _mean(values: list[float]) -> float | None:
    return round(sum(values) / len(values), 1) if values else None


def choose(courses: list[CourseInfo], condition: str) -> CourseInfo | None:
    """춥거나 더우면 가장 짧은 코스, 그 외(쾌적, 알 수 없음)에는 가장 긴 코스. 같으면 번호가 작은 코스."""
    if not courses:
        return None
    if condition in (COLD, HOT):
        return min(courses, key=lambda c: (c.total_minutes, c.id))
    return max(courses, key=lambda c: (c.total_minutes, -c.id))


def _reason(condition: str, temperature: float | None, course: CourseInfo) -> str:
    if condition == COLD:
        return f"기온 {temperature:.1f}도로 추워서 가장 짧은 코스({course.name})를 추천합니다."
    if condition == HOT:
        return f"기온 {temperature:.1f}도로 더워서 가장 짧은 코스({course.name})를 추천합니다."
    if condition == COMFORTABLE:
        return f"기온 {temperature:.1f}도로 활동하기 좋아 가장 긴 코스({course.name})를 추천합니다."
    return f"기온을 알 수 없어 기본 코스(가장 긴 코스, {course.name})를 추천합니다."


def build(
    courses: list[CourseInfo],
    spot_temperatures: dict[int, float | None],
    cold_max: int,
    hot_min: int,
) -> RecommendationOut:
    """코스 목록과 장소별 현재 기온으로 추천을 만든다.

    spot_temperatures: 장소 번호 -> 현재 기온(℃). 값이 없거나 오래됐으면 None.
    """
    spot_ids = {sid for c in courses for sid in c.spot_ids}
    known = [spot_temperatures[sid] for sid in spot_ids if spot_temperatures.get(sid) is not None]
    temperature = _mean(known)
    condition = classify(temperature, cold_max, hot_min)
    chosen = choose(courses, condition)

    return RecommendationOut(
        condition=condition,
        temperature=temperature,
        sample_count=len(known),
        thresholds=ThresholdsOut(cold_max=cold_max, hot_min=hot_min),
        recommended_course_id=chosen.id if chosen else None,
        recommended_course_name=chosen.name if chosen else None,
        reason=_reason(condition, temperature, chosen) if chosen else "추천할 코스가 없습니다.",
        courses=[
            CourseChoiceOut(
                id=c.id,
                name=c.name,
                total_minutes=c.total_minutes,
                average_temperature=_mean(
                    [spot_temperatures[sid] for sid in c.spot_ids if spot_temperatures.get(sid) is not None]
                ),
                recommended=chosen is not None and c.id == chosen.id,
            )
            for c in courses
        ],
    )
