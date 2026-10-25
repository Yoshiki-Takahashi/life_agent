from logging.config import fileConfig

from alembic import context
from sqlalchemy import URL, engine_from_config, pool

from life_agent_core import models  # noqa: F401
from life_agent_core.config import get_settings
from life_agent_core.database import Base

config = context.config
connection_url = get_settings().database_connection_url
database_url = (
    connection_url.render_as_string(hide_password=False)
    if isinstance(connection_url, URL)
    else connection_url
)
config.set_main_option("sqlalchemy.url", database_url.replace("%", "%%"))

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
