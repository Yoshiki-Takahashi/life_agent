from collections.abc import Generator
from typing import Annotated

from fastapi import Depends, HTTPException
from sqlalchemy import create_engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from life_agent_core.config import get_settings


class Base(DeclarativeBase):
    pass


engine = create_engine(get_settings().database_connection_url)
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


def get_db() -> Generator[Session]:
    with SessionLocal() as session:
        yield session


DatabaseSession = Annotated[Session, Depends(get_db)]


def commit(db: Session) -> None:
    try:
        db.commit()
    except SQLAlchemyError as error:
        db.rollback()
        raise HTTPException(
            status_code=500, detail="保存できませんでした。再試行してください"
        ) from error
