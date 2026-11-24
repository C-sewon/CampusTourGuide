"""설계 문서(docs/backend-design.md)의 ERD가 실제 DB 표와 어긋나지 않는지 확인한다.

표나 칸을 추가하거나 바꿨는데 ERD 그림을 안 고치면 이 테스트가 실패한다.
(is_core, has_card 를 추가하고 ERD 를 고치지 않아 어긋났던 일에서 만들었다)
"""
import re
from pathlib import Path

import pytest

from app import models
from app.database import Base

DOC = Path(__file__).resolve().parent.parent / "docs" / "backend-design.md"


def erd_columns():
    """ERD(mermaid erDiagram)의 표 이름 -> 칸 이름 목록."""
    text = DOC.read_text(encoding="utf-8")
    start = text.index("erDiagram")
    block = text[start : text.index("```", start)]
    tables: dict[str, list[str]] = {}
    current = None
    for line in block.splitlines():
        entity = re.match(r"\s*([A-Z_]+)\s*\{\s*$", line)
        if entity:
            current = entity.group(1).lower()
            tables[current] = []
        elif current and line.strip() == "}":
            current = None
        elif current and line.strip():
            parts = line.split()
            tables[current].append(parts[1])  # "int id PK ..." 에서 두 번째가 칸 이름
    return tables


def model_columns():
    return {table.name: list(table.columns.keys()) for table in Base.metadata.sorted_tables}


def test_every_table_is_in_the_erd_and_nothing_extra():
    assert set(erd_columns()) == set(model_columns()), "ERD 의 표 목록이 실제 DB 표와 다릅니다"


@pytest.mark.parametrize("table", sorted(model_columns()))
def test_erd_lists_exactly_the_columns_of_each_table(table):
    erd = set(erd_columns().get(table, []))
    actual = set(model_columns()[table])
    missing = sorted(actual - erd)
    extra = sorted(erd - actual)
    assert not missing and not extra, (
        f"{table}: ERD 에 없는 칸 {missing}, 코드에 없는 칸 {extra}. docs/backend-design.md 의 ERD 를 고쳐 주세요."
    )


def test_the_core_and_card_columns_are_documented():
    columns = erd_columns()
    assert "is_core" in columns["courses"]
    assert "has_card" in columns["spots"]


def test_models_import_registers_all_tables():
    # models 를 import 하지 않으면 Base 에 표가 등록되지 않아 위 테스트가 비어 버린다
    assert models.Course.__tablename__ in model_columns()
